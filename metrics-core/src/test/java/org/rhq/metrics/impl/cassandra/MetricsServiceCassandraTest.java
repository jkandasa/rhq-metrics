package org.rhq.metrics.impl.cassandra;

import static java.util.Arrays.asList;
import static org.joda.time.DateTime.now;
import static org.rhq.metrics.core.AvailabilityType.DOWN;
import static org.rhq.metrics.core.AvailabilityType.UP;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.datastax.driver.core.ResultSetFuture;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;

import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Hours;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.rhq.metrics.core.Availability;
import org.rhq.metrics.core.AvailabilityMetric;
import org.rhq.metrics.core.Counter;
import org.rhq.metrics.core.Interval;
import org.rhq.metrics.core.Metric;
import org.rhq.metrics.core.MetricAlreadyExistsException;
import org.rhq.metrics.core.MetricData;
import org.rhq.metrics.core.MetricId;
import org.rhq.metrics.core.MetricType;
import org.rhq.metrics.core.NumericData;
import org.rhq.metrics.core.NumericMetric2;
import org.rhq.metrics.core.Tag;
import org.rhq.metrics.core.Tenant;
import org.rhq.metrics.test.MetricsTest;

/**
 * @author John Sanda
 */
public class MetricsServiceCassandraTest extends MetricsTest {

    private MetricsServiceCassandra metricsService;

    private DataAccess dataAccess;

    @BeforeClass
    public void initClass() {
        initSession();
        metricsService = new MetricsServiceCassandra();
        metricsService.startUp(session);
        dataAccess = metricsService.getDataAccess();
    }

    @BeforeMethod
    public void initMethod() {
        session.execute("TRUNCATE tenants");
        session.execute("TRUNCATE data");
        session.execute("TRUNCATE tags");
        session.execute("TRUNCATE metrics_idx");
        metricsService.setDataAccess(dataAccess);
    }

    @Test
    public void createAndFindMetrics() throws Exception {
        NumericMetric2 m1 = new NumericMetric2("t1", new MetricId("m1"), ImmutableMap.of("a1", "1", "a2", "2"));
        ListenableFuture<Void> insertFuture = metricsService.createMetric(m1);
        getUninterruptibly(insertFuture);

        ListenableFuture<Metric> queryFuture = metricsService.findMetric(m1.getTenantId(), m1.getType(), m1.getId());
        Metric actual = getUninterruptibly(queryFuture);
        assertEquals(actual, m1, "The metric does not match the expected value");

        AvailabilityMetric m2 = new AvailabilityMetric("t1", new MetricId("m2"), ImmutableMap.of("a3", "3", "a4", "4"));
        insertFuture = metricsService.createMetric(m2);
        getUninterruptibly(insertFuture);

        queryFuture = metricsService.findMetric(m2.getTenantId(), m2.getType(), m2.getId());
        actual = getUninterruptibly(queryFuture);
        assertEquals(actual, m2, "The metric does not match the expected value");

        insertFuture = metricsService.createMetric(m1);
        Throwable exception = null;
        try {
            getUninterruptibly(insertFuture);
        } catch (Exception e) {
            exception = e.getCause();
        }
        assertTrue(exception != null && exception instanceof MetricAlreadyExistsException,
            "Expected a " + MetricAlreadyExistsException.class.getSimpleName() + " to be thrown");

        assertMetricIndexMatches("t1", MetricType.NUMERIC, asList(m1));
        assertMetricIndexMatches("t1", MetricType.AVAILABILITY, asList(m2));
    }

    @Test
    public void updateMetadata() throws Exception {
        NumericMetric2 metric = new NumericMetric2("t1", new MetricId("m1"), ImmutableMap.of("a1", "1", "a2", "2"));
        ListenableFuture<Void> insertFuture = metricsService.createMetric(metric);
        getUninterruptibly(insertFuture);

        Map<String, String> additions = ImmutableMap.of("a2", "two", "a3", "3");
        Set<String> deletions = ImmutableSet.of("a1");
        insertFuture = metricsService.updateMetadata(metric, additions, deletions);
        getUninterruptibly(insertFuture);

        ListenableFuture<Metric> queryFuture = metricsService.findMetric(metric.getTenantId(), MetricType.NUMERIC,
            metric.getId());
        Metric updatedMetric = getUninterruptibly(queryFuture);

        assertEquals(updatedMetric.getMetadata(), ImmutableMap.of("a2", "two", "a3", "3"),
            "The updated meta data does not match the expected values");

        assertMetricIndexMatches(metric.getTenantId(), MetricType.NUMERIC, asList(updatedMetric));
    }

    @Test
    public void addAndFetchRawData() throws Exception {
        DateTime start = now().minusMinutes(30);
        DateTime end = start.plusMinutes(20);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId("t1")));

        NumericMetric2 metric = new NumericMetric2("t1", new MetricId("m1"));
        metric.addData(start.getMillis(), 1.1);
        metric.addData(start.plusMinutes(2).getMillis(), 2.2);
        metric.addData(start.plusMinutes(4).getMillis(), 3.3);
        metric.addData(end.getMillis(), 4.4);

        ListenableFuture<Void> insertFuture = metricsService.addNumericData(asList(metric));
        getUninterruptibly(insertFuture);

        ListenableFuture<List<NumericData>> queryFuture = metricsService.findData(metric, start.getMillis(),
            end.getMillis());
        List<NumericData> actual = getUninterruptibly(queryFuture);
        List<NumericData> expected = asList(
            new NumericData(metric, start.plusMinutes(4).getMillis(), 3.3),
            new NumericData(metric, start.plusMinutes(2).getMillis(), 2.2),
            new NumericData(metric, start.getMillis(), 1.1)
        );

        assertEquals(actual, expected, "The data does not match the expected values");

        assertMetricIndexMatches("t1", MetricType.NUMERIC, asList(metric));
    }

    @Test
    public void verifyTTLsSetOnNumericData() throws Exception {
        DateTime start = now().minusMinutes(10);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId("t1")));
        getUninterruptibly(metricsService.createTenant(new Tenant().setId("t2")
            .setRetention(MetricType.NUMERIC, Days.days(14).toStandardHours().getHours())));

        VerifyTTLDataAccess verifyTTLDataAccess = new VerifyTTLDataAccess(dataAccess);

        metricsService.loadTenants();
        metricsService.setDataAccess(verifyTTLDataAccess);

        NumericMetric2 m1 = new NumericMetric2("t1", new MetricId("m1"));
        m1.addData(start.getMillis(), 1.01);
        m1.addData(start.plusMinutes(1).getMillis(), 1.02);
        m1.addData(start.plusMinutes(2).getMillis(), 1.03);
        getUninterruptibly(metricsService.addNumericData(asList(m1)));

        Set<String> tags = ImmutableSet.of("tag1");


        // Sleep for 5 seconds and verify that the TTL of the tagged data points is at
        // least 5 seconds less than the original TTL.
        Thread.sleep(5000);
        verifyTTLDataAccess.setNumericTagTTL(Days.days(14).toStandardSeconds().minus(5).getSeconds());
        getUninterruptibly(metricsService.tagNumericData(m1, tags, start.getMillis(),
            start.plusMinutes(2).getMillis()));

        verifyTTLDataAccess.setNumericTTL(Days.days(14).toStandardSeconds().getSeconds());
        NumericMetric2 m2 = new NumericMetric2("t2", new MetricId("m2"));
        m2.addData(start.plusMinutes(5).getMillis(), 2.02);
        getUninterruptibly(metricsService.addNumericData(asList(m2)));

        // Sleep for 3 seconds and verify that the TTL of the tagged data points is at
        // least 3 seconds less than the original TTL.
        Thread.sleep(3000);
        verifyTTLDataAccess.setNumericTagTTL(Days.days(14).toStandardSeconds().minus(3).getSeconds());
        getUninterruptibly(metricsService.tagNumericData(m2, tags, start.plusMinutes(5).getMillis()));

        getUninterruptibly(metricsService.createTenant(new Tenant().setId("t3")
            .setRetention(MetricType.NUMERIC, 24)));
        verifyTTLDataAccess.setNumericTTL(Hours.hours(24).toStandardSeconds().getSeconds());
        NumericMetric2 m3 = new NumericMetric2("t3", new MetricId("m3"));
        m3.addData(start.getMillis(), 3.03);
        getUninterruptibly(metricsService.addNumericData(asList(m3)));
    }

    @Test
    public void verifyTTLsSetOnAvailabilityData() throws Exception {
        DateTime start = now().minusMinutes(10);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId("t1")));
        getUninterruptibly(metricsService.createTenant(new Tenant().setId("t2")
            .setRetention(MetricType.AVAILABILITY, Days.days(14).toStandardHours().getHours())));

        VerifyTTLDataAccess verifyTTLDataAccess = new VerifyTTLDataAccess(dataAccess);

        metricsService.loadTenants();
        metricsService.setDataAccess(verifyTTLDataAccess);

        AvailabilityMetric m1 = new AvailabilityMetric("t1", new MetricId("m1"));
        m1.addData(new Availability(start.getMillis(), UP));
        m1.addData(new Availability(start.plusMinutes(1).getMillis(), DOWN));
        m1.addData(new Availability(start.plusMinutes(2).getMillis(), DOWN));
        getUninterruptibly(metricsService.addAvailabilityData(asList(m1)));

        Set<String> tags = ImmutableSet.of("tag1");

        // Sleep for 5 seconds and verify that the TTL of the tagged data points is at
        // least 5 seconds less than the original TTL.
        Thread.sleep(5000);
        verifyTTLDataAccess.setAvailabilityTagTTL(Days.days(14).toStandardSeconds().minus(5).getSeconds());
        getUninterruptibly(metricsService.tagAvailabilityData(m1, tags, start.getMillis(),
            start.plusMinutes(2).getMillis()));

        verifyTTLDataAccess.setAvailabilityTTL(Days.days(14).toStandardSeconds().getSeconds());
        AvailabilityMetric m2 = new AvailabilityMetric("t2", new MetricId("m2"));
        m2.addData(new Availability(start.plusMinutes(5).getMillis(), UP));
        getUninterruptibly(metricsService.addAvailabilityData(asList(m2)));

        // Sleep for 3 seconds and verify that the TTL of the tagged data points is at
        // least 3 seconds less than the original TTL.
        Thread.sleep(3000);
        verifyTTLDataAccess.setAvailabilityTagTTL(Days.days(14).toStandardSeconds().minus(3).getSeconds());
        getUninterruptibly(metricsService.tagAvailabilityData(m2, tags, start.plusMinutes(5).getMillis()));

        getUninterruptibly(metricsService.createTenant(new Tenant().setId("t3")
            .setRetention(MetricType.AVAILABILITY, 24)));
        verifyTTLDataAccess.setAvailabilityTTL(Hours.hours(24).toStandardSeconds().getSeconds());
        AvailabilityMetric m3 = new AvailabilityMetric("t3", new MetricId("m3"));
        m3.addData(new Availability(start.getMillis(), UP));
        getUninterruptibly(metricsService.addAvailabilityData(asList(m3)));
    }

    @Test
    public void fetchNumericDataThatHasTags() throws Exception {
        DateTime end = now();
        DateTime start = end.minusMinutes(10);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId("tenant1")));

        NumericMetric2 metric = new NumericMetric2("tenant1", new MetricId("m1"));
        metric.addData(start.getMillis(), 100.0);
        metric.addData(start.plusMinutes(1).getMillis(), 101.1);
        metric.addData(start.plusMinutes(2).getMillis(), 102.2);
        metric.addData(start.plusMinutes(3).getMillis(), 103.3);
        metric.addData(start.plusMinutes(4).getMillis(), 104.4);
        metric.addData(start.plusMinutes(5).getMillis(), 105.5);
        metric.addData(start.plusMinutes(6).getMillis(), 106.6);

        ListenableFuture<Void> insertFuture = metricsService.addNumericData(asList(metric));
        getUninterruptibly(insertFuture);

        ListenableFuture<List<NumericData>> tagFuture = metricsService.tagNumericData(metric,
            ImmutableSet.of("t1", "t2"), start.plusMinutes(2).getMillis());
        getUninterruptibly(tagFuture);

        tagFuture = metricsService.tagNumericData(metric, ImmutableSet.of("t3", "t4"), start.plusMinutes(3).getMillis(),
            start.plusMinutes(5).getMillis());
        getUninterruptibly(tagFuture);

        ListenableFuture<List<NumericData>> queryFuture = metricsService.findData(metric, start.getMillis(),
            end.getMillis());
        List<NumericData> actual = getUninterruptibly(queryFuture);
        List<NumericData> expected = asList(
            new NumericData(metric, start.plusMinutes(6).getMillis(), 106.6),
            new NumericData(metric, start.plusMinutes(5).getMillis(), 105.5),
            new NumericData(metric, start.plusMinutes(4).getMillis(), 104.4),
            new NumericData(metric, start.plusMinutes(3).getMillis(), 103.3),
            new NumericData(metric, start.plusMinutes(2).getMillis(), 102.2),
            new NumericData(metric, start.plusMinutes(1).getMillis(), 101.1),
            new NumericData(metric, start.getMillis(), 100.0)
        );

        assertEquals(actual, expected, "The data does not match the expected values");
        assertEquals(actual.get(3).getTags(), ImmutableSet.of(new Tag("t3"), new Tag("t4")), "The tags do not match");
        assertEquals(actual.get(2).getTags(), ImmutableSet.of(new Tag("t3"), new Tag("t4")), "The tags do not match");
        assertEquals(actual.get(2).getTags(), ImmutableSet.of(new Tag("t3"), new Tag("t4")), "The tags do not match");
        assertEquals(actual.get(4).getTags(), ImmutableSet.of(new Tag("t1"), new Tag("t2")), "The tags do not match");
    }

    @Test
    public void addDataForMultipleMetrics() throws Exception {
        DateTime start = now().minusMinutes(10);
        DateTime end = start.plusMinutes(8);
        String tenantId = "test-tenant";

        getUninterruptibly(metricsService.createTenant(new Tenant().setId(tenantId)));

        NumericMetric2 m1 = new NumericMetric2(tenantId, new MetricId("m1"));
        m1.addData(start.plusSeconds(30).getMillis(), 11.2);
        m1.addData(start.getMillis(), 11.1);

        NumericMetric2 m2 = new NumericMetric2(tenantId, new MetricId("m2"));
        m2.addData(start.plusSeconds(30).getMillis(), 12.2);
        m2.addData(start.getMillis(), 12.1);

        NumericMetric2 m3 = new NumericMetric2(tenantId, new MetricId("m3"));

        ListenableFuture<Void> insertFuture = metricsService.addNumericData(asList(m1, m2, m3));
        getUninterruptibly(insertFuture);

        ListenableFuture<NumericMetric2> queryFuture = metricsService.findNumericData(m1, start.getMillis(),
            end.getMillis());
        NumericMetric2 actual = getUninterruptibly(queryFuture);
        assertMetricEquals(actual, m1);

        queryFuture = metricsService.findNumericData(m2, start.getMillis(), end.getMillis());
        actual = getUninterruptibly(queryFuture);
        assertMetricEquals(actual, m2);

        queryFuture = metricsService.findNumericData(m3, start.getMillis(), end.getMillis());
        actual = getUninterruptibly(queryFuture);
        assertNull(actual, "Did not expect to get back results since there is no data for " + m3);

        assertMetricIndexMatches(tenantId, MetricType.NUMERIC, asList(m1, m2, m3));
    }

    @Test
    public void addAvailabilityForMultipleMetrics() throws Exception {
        DateTime start = now().minusMinutes(10);
        DateTime end = start.plusMinutes(8);
        String tenantId = "test-tenant";

        getUninterruptibly(metricsService.createTenant(new Tenant().setId(tenantId)));

        AvailabilityMetric m1 = new AvailabilityMetric(tenantId, new MetricId("m1"));
        m1.addData(new Availability(m1, start.plusSeconds(20).getMillis(), "down"));
        m1.addData(new Availability(m1, start.plusSeconds(10).getMillis(), "up"));

        AvailabilityMetric m2 = new AvailabilityMetric(tenantId, new MetricId("m2"));
        m2.addData(new Availability(m2, start.plusSeconds(30).getMillis(), "up"));
        m2.addData(new Availability(m2, start.plusSeconds(15).getMillis(), "down"));

        AvailabilityMetric m3 = new AvailabilityMetric(tenantId, new MetricId("m3"));

        ListenableFuture<Void> insertFuture = metricsService.addAvailabilityData(asList(m1, m2, m3));
        getUninterruptibly(insertFuture);

        ListenableFuture<AvailabilityMetric> queryFuture = metricsService.findAvailabilityData(m1, start.getMillis(),
            end.getMillis());
        AvailabilityMetric actual = getUninterruptibly(queryFuture);
        assertMetricEquals(actual, m1);

        queryFuture = metricsService.findAvailabilityData(m2, start.getMillis(), end.getMillis());
        actual = getUninterruptibly(queryFuture);
        assertMetricEquals(actual, m2);

        queryFuture = metricsService.findAvailabilityData(m3, start.getMillis(), end.getMillis());
        actual = getUninterruptibly(queryFuture);
        assertNull(actual, "Did not expect to get back results since there is no data for " + m3);

        assertMetricIndexMatches(tenantId, MetricType.AVAILABILITY, asList(m1, m2, m3));
    }

    @Test
    public void fetchAvailabilityDataThatHasTags() throws Exception {
        DateTime end = now();
        DateTime start = end.minusMinutes(10);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId("tenant1")));

        AvailabilityMetric metric = new AvailabilityMetric("tenant1", new MetricId("A1"));
        metric.addAvailability(start.getMillis(), UP);
        metric.addAvailability(start.plusMinutes(1).getMillis(), DOWN);
        metric.addAvailability(start.plusMinutes(2).getMillis(), DOWN);
        metric.addAvailability(start.plusMinutes(3).getMillis(), UP);
        metric.addAvailability(start.plusMinutes(4).getMillis(), DOWN);
        metric.addAvailability(start.plusMinutes(5).getMillis(), UP);
        metric.addAvailability(start.plusMinutes(6).getMillis(), UP);

        ListenableFuture<Void> insertFuture = metricsService.addAvailabilityData(asList(metric));
        getUninterruptibly(insertFuture);

        ListenableFuture<List<Availability>> tagFuture = metricsService.tagAvailabilityData(metric,
            ImmutableSet.of("t1", "t2"), start.plusMinutes(2).getMillis());
        getUninterruptibly(tagFuture);

        tagFuture = metricsService.tagAvailabilityData(metric, ImmutableSet.of("t3", "t4"),
            start.plusMinutes(3).getMillis(), start.plusMinutes(5).getMillis());
        getUninterruptibly(tagFuture);

        ListenableFuture<AvailabilityMetric> queryFuture = metricsService.findAvailabilityData(metric, start.getMillis(),
            end.getMillis());
        AvailabilityMetric actualMetric = getUninterruptibly(queryFuture);
        List<Availability> actual = actualMetric.getData();
        List<Availability> expected = asList(
            new Availability(metric, start.plusMinutes(6).getMillis(), UP),
            new Availability(metric, start.plusMinutes(5).getMillis(), UP),
            new Availability(metric, start.plusMinutes(4).getMillis(), DOWN),
            new Availability(metric, start.plusMinutes(3).getMillis(), UP),
            new Availability(metric, start.plusMinutes(2).getMillis(), DOWN),
            new Availability(metric, start.plusMinutes(1).getMillis(), DOWN),
            new Availability(metric, start.getMillis(), UP)
        );

        assertEquals(actual, expected, "The data does not match the expected values");
        assertEquals(actual.get(3).getTags(), ImmutableSet.of(new Tag("t3"), new Tag("t4")), "The tags do not match");
        assertEquals(actual.get(2).getTags(), ImmutableSet.of(new Tag("t3"), new Tag("t4")), "The tags do not match");
        assertEquals(actual.get(2).getTags(), ImmutableSet.of(new Tag("t3"), new Tag("t4")), "The tags do not match");
        assertEquals(actual.get(4).getTags(), ImmutableSet.of(new Tag("t1"), new Tag("t2")), "The tags do not match");
    }

    @Test
    public void tagNumericDataByDateRangeAndQueryByMultipleTags() throws Exception {
        String tenant = "tag-test";
        DateTime start = now().minusMinutes(20);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId(tenant)));

        NumericData d1 = new NumericData(start.getMillis(), 101.1);
        NumericData d2 = new NumericData(start.plusMinutes(2).getMillis(), 101.2);
        NumericData d3 = new NumericData(start.plusMinutes(6).getMillis(), 102.2);
        NumericData d4 = new NumericData(start.plusMinutes(8).getMillis(), 102.3);
        NumericData d5 = new NumericData(start.plusMinutes(4).getMillis(), 102.1);
        NumericData d6 = new NumericData(start.plusMinutes(4).getMillis(), 101.4);
        NumericData d7 = new NumericData(start.plusMinutes(10).getMillis(), 102.4);
        NumericData d8 = new NumericData(start.plusMinutes(6).getMillis(), 103.1);
        NumericData d9 = new NumericData(start.plusMinutes(7).getMillis(), 103.1);

        NumericMetric2 m1 = new NumericMetric2(tenant, new MetricId("m1"));
        m1.addData(d1);
        m1.addData(d2);
        m1.addData(d6);

        NumericMetric2 m2 = new NumericMetric2(tenant, new MetricId("m2"));
        m2.addData(d3);
        m2.addData(d4);
        m2.addData(d5);
        m2.addData(d7);

        NumericMetric2 m3 = new NumericMetric2(tenant, new MetricId("m3"));
        m3.addData(d8);
        m3.addData(d9);

        ListenableFuture<Void> insertFuture = metricsService.addNumericData(asList(m1, m2, m3));
        getUninterruptibly(insertFuture);

        ListenableFuture<List<NumericData>> tagFuture1 = metricsService.tagNumericData(m1, ImmutableSet.of("t1"),
            start.getMillis(), start.plusMinutes(6).getMillis());
        ListenableFuture<List<NumericData>> tagFuture2 = metricsService.tagNumericData(m2, ImmutableSet.of("t1"),
            start.getMillis(), start.plusMinutes(6).getMillis());
        ListenableFuture<List<NumericData>> tagFuture3 = metricsService.tagNumericData(m1, ImmutableSet.of("t2"),
            start.plusMinutes(4).getMillis(), start.plusMinutes(8).getMillis());
        ListenableFuture<List<NumericData>> tagFuture4 = metricsService.tagNumericData(m2, ImmutableSet.of("t2"),
            start.plusMinutes(4).getMillis(), start.plusMinutes(8).getMillis());
        ListenableFuture<List<NumericData>> tagFuture5 = metricsService.tagNumericData(m3, ImmutableSet.of("t2"),
            start.plusMinutes(4).getMillis(), start.plusMinutes(8).getMillis());

        getUninterruptibly(tagFuture1);
        getUninterruptibly(tagFuture2);
        getUninterruptibly(tagFuture3);
        getUninterruptibly(tagFuture4);
        getUninterruptibly(tagFuture5);

        ListenableFuture<Map<MetricId, Set<NumericData>>> queryFuture = metricsService.findNumericDataByTags(tenant,
            ImmutableSet.of("t1", "t2"));
        Map<MetricId, Set<NumericData>> actual = getUninterruptibly(queryFuture);
        ImmutableMap<MetricId, ImmutableSet<NumericData>> expected = ImmutableMap.of(
            new MetricId("m1"), ImmutableSet.of(d1, d2, d6),
            new MetricId("m2"), ImmutableSet.of(d5, d3)
        );

        assertEquals(actual, expected, "The tagged data does not match");
    }

    @Test
    public void tagAvailabilityByDateRangeAndQueryByMultipleTags() throws Exception {
        String tenant = "tag-test";
        DateTime start = now().minusMinutes(20);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId(tenant)));

        AvailabilityMetric m1 = new AvailabilityMetric(tenant, new MetricId("m1"));
        AvailabilityMetric m2 = new AvailabilityMetric(tenant, new MetricId("m2"));
        AvailabilityMetric m3 = new AvailabilityMetric(tenant, new MetricId("m3"));

        Availability a1 = new Availability(m1, start.getMillis(), UP);
        Availability a2 = new Availability(m1, start.plusMinutes(2).getMillis(), UP);
        Availability a3 = new Availability(m2, start.plusMinutes(6).getMillis(), DOWN);
        Availability a4 = new Availability(m2, start.plusMinutes(8).getMillis(), DOWN);
        Availability a5 = new Availability(m2, start.plusMinutes(4).getMillis(), UP);
        Availability a6 = new Availability(m1, start.plusMinutes(4).getMillis(), DOWN);
        Availability a7 = new Availability(m2, start.plusMinutes(10).getMillis(), UP);
        Availability a8 = new Availability(m3, start.plusMinutes(6).getMillis(), DOWN);
        Availability a9 = new Availability(m3, start.plusMinutes(7).getMillis(), UP);

        m1.addData(a1);
        m1.addData(a2);
        m1.addData(a6);

        m2.addData(a3);
        m2.addData(a4);
        m2.addData(a5);
        m2.addData(a7);

        m3.addData(a8);
        m3.addData(a9);

        ListenableFuture<Void> insertFuture = metricsService.addAvailabilityData(asList(m1, m2, m3));
        getUninterruptibly(insertFuture);

        ListenableFuture<List<Availability>> tagFuture1 = metricsService.tagAvailabilityData(m1, ImmutableSet.of("t1"),
            start.getMillis(), start.plusMinutes(6).getMillis());
        ListenableFuture<List<Availability>> tagFuture2 = metricsService.tagAvailabilityData(m2, ImmutableSet.of("t1"),
            start.getMillis(), start.plusMinutes(6).getMillis());
        ListenableFuture<List<Availability>> tagFuture3 = metricsService.tagAvailabilityData(m1, ImmutableSet.of("t2"),
            start.plusMinutes(4).getMillis(), start.plusMinutes(8).getMillis());
        ListenableFuture<List<Availability>> tagFuture4 = metricsService.tagAvailabilityData(m2, ImmutableSet.of("t2"),
            start.plusMinutes(4).getMillis(), start.plusMinutes(8).getMillis());
        ListenableFuture<List<Availability>> tagFuture5 = metricsService.tagAvailabilityData(m3, ImmutableSet.of("t2"),
            start.plusMinutes(4).getMillis(), start.plusMinutes(8).getMillis());

        getUninterruptibly(tagFuture1);
        getUninterruptibly(tagFuture2);
        getUninterruptibly(tagFuture3);
        getUninterruptibly(tagFuture4);
        getUninterruptibly(tagFuture5);

        ListenableFuture<Map<MetricId, Set<Availability>>> queryFuture = metricsService.findAvailabilityByTags(tenant,
            ImmutableSet.of("t1", "t2"));
        Map<MetricId, Set<Availability>> actual = getUninterruptibly(queryFuture);
        ImmutableMap<MetricId, ImmutableSet<Availability>> expected = ImmutableMap.of(
            new MetricId("m1"), ImmutableSet.of(a1, a2, a6),
            new MetricId("m2"), ImmutableSet.of(a5, a3)
        );

        assertEquals(actual, expected, "The tagged data does not match");
    }

    @Test
    public void tagIndividualNumericDataPoints() throws Exception {
        String tenant = "tag-test";
        DateTime start = now().minusMinutes(20);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId(tenant)));

        NumericData d1 = new NumericData(start.getMillis(), 101.1);
        NumericData d2 = new NumericData(start.plusMinutes(2).getMillis(), 101.2);
        NumericData d3 = new NumericData(start.plusMinutes(6).getMillis(), 102.2);
        NumericData d4 = new NumericData(start.plusMinutes(8).getMillis(), 102.3);
        NumericData d5 = new NumericData(start.plusMinutes(4).getMillis(), 102.1);
        NumericData d6 = new NumericData(start.plusMinutes(4).getMillis(), 101.4);
        NumericData d7 = new NumericData(start.plusMinutes(10).getMillis(), 102.4);
        NumericData d8 = new NumericData(start.plusMinutes(6).getMillis(), 103.1);
        NumericData d9 = new NumericData(start.plusMinutes(7).getMillis(), 103.1);

        NumericMetric2 m1 = new NumericMetric2(tenant, new MetricId("m1"));
        m1.addData(d1);
        m1.addData(d2);
        m1.addData(d6);

        NumericMetric2 m2 = new NumericMetric2(tenant, new MetricId("m2"));
        m2.addData(d3);
        m2.addData(d4);
        m2.addData(d5);
        m2.addData(d7);

        NumericMetric2 m3 = new NumericMetric2(tenant, new MetricId("m3"));
        m3.addData(d8);
        m3.addData(d9);


        ListenableFuture<Void> insertFuture = metricsService.addNumericData(asList(m1, m2, m3));
        getUninterruptibly(insertFuture);

        ListenableFuture<List<NumericData>> tagFuture = metricsService.tagNumericData(m1, ImmutableSet.of("t1"),
            d1.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(d1), "Tagging " + d1 + " returned unexpected results");

        tagFuture = metricsService.tagNumericData(m1, ImmutableSet.of("t1", "t2", "t3"), d2.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(d2), "Tagging " + d2 + " returned unexpected results");

        tagFuture = metricsService.tagNumericData(m1, ImmutableSet.of("t1"), start.minusMinutes(10).getMillis());
        assertEquals(getUninterruptibly(tagFuture), Collections.emptyList(),
            "No data should be returned since there is no data for this time");

        tagFuture = metricsService.tagNumericData(m2, ImmutableSet.of("t2", "t3"), d3.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(d3), "Tagging " + d3 + " returned unexpected results");

        tagFuture = metricsService.tagNumericData(m2, ImmutableSet.of("t3", "t4"), d4.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(d4), "Tagging " + d4 + " returned unexpected results");

        ListenableFuture<Map<MetricId, Set<NumericData>>> queryFuture = metricsService.findNumericDataByTags(tenant,
            ImmutableSet.of("t2", "t3"));
        Map<MetricId, Set<NumericData>> actual = getUninterruptibly(queryFuture);
        ImmutableMap<MetricId, ImmutableSet<NumericData>> expected = ImmutableMap.of(
            new MetricId("m1"), ImmutableSet.of(d2),
            new MetricId("m2"), ImmutableSet.of(d3, d4)
        );

        assertEquals(actual, expected, "The tagged data does not match");
    }

    @Test
    public void tagIndividualAvailabilityDataPoints() throws Exception {
        String tenant = "tag-test";
        DateTime start = now().minusMinutes(20);

        getUninterruptibly(metricsService.createTenant(new Tenant().setId(tenant)));

        AvailabilityMetric m1 = new AvailabilityMetric(tenant, new MetricId("m1"));
        AvailabilityMetric m2 = new AvailabilityMetric(tenant, new MetricId("m2"));
        AvailabilityMetric m3 = new AvailabilityMetric(tenant, new MetricId("m3"));

        Availability a1 = new Availability(m1, start.getMillis(), UP);
        Availability a2 = new Availability(m1, start.plusMinutes(2).getMillis(), UP);
        Availability a3 = new Availability(m2, start.plusMinutes(6).getMillis(), DOWN);
        Availability a4 = new Availability(m2, start.plusMinutes(8).getMillis(), DOWN);
        Availability a5 = new Availability(m2, start.plusMinutes(4).getMillis(), UP);
        Availability a6 = new Availability(m1, start.plusMinutes(4).getMillis(), DOWN);
        Availability a7 = new Availability(m2, start.plusMinutes(10).getMillis(), UP);
        Availability a8 = new Availability(m3, start.plusMinutes(6).getMillis(), DOWN);
        Availability a9 = new Availability(m3, start.plusMinutes(7).getMillis(), UP);

        m1.addData(a1);
        m1.addData(a2);
        m1.addData(a6);

        m2.addData(a3);
        m2.addData(a4);
        m2.addData(a5);
        m2.addData(a7);

        m3.addData(a8);
        m3.addData(a9);

        ListenableFuture<Void> insertFuture = metricsService.addAvailabilityData(asList(m1, m2, m3));
        getUninterruptibly(insertFuture);

        ListenableFuture<List<Availability>> tagFuture = metricsService.tagAvailabilityData(m1, ImmutableSet.of("t1"),
            a1.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(a1), "Tagging " + a1 + " returned unexpected results");

        tagFuture = metricsService.tagAvailabilityData(m1, ImmutableSet.of("t1", "t2", "t3"), a2.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(a2), "Tagging " + a2 + " returned unexpected results");

        tagFuture = metricsService.tagAvailabilityData(m1, ImmutableSet.of("t1"), start.minusMinutes(10).getMillis());
        assertEquals(getUninterruptibly(tagFuture), Collections.emptyList(),
            "No data should be returned since there is no data for this time");

        tagFuture = metricsService.tagAvailabilityData(m2, ImmutableSet.of("t2", "t3"), a3.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(a3), "Tagging " + a3 + " returned unexpected results");

        tagFuture = metricsService.tagAvailabilityData(m2, ImmutableSet.of("t3", "t4"), a4.getTimestamp());
        assertEquals(getUninterruptibly(tagFuture), asList(a4), "Tagging " + a4 + " returned unexpected results");

        ListenableFuture<Map<MetricId, Set<Availability>>> queryFuture = metricsService.findAvailabilityByTags(tenant,
            ImmutableSet.of("t2", "t3"));
        Map<MetricId, Set<Availability>> actual = getUninterruptibly(queryFuture);
        ImmutableMap<MetricId, ImmutableSet<Availability>> expected = ImmutableMap.of(
            new MetricId("m1"), ImmutableSet.of(a2),
            new MetricId("m2"), ImmutableSet.of(a3, a4)
        );

        assertEquals(actual, expected, "The tagged data does not match");
    }

    private void assertMetricEquals(Metric actual, Metric expected) {
        assertEquals(actual, expected, "The metric doe not match the expected value");
        assertEquals(actual.getData(), expected.getData(), "The data does not match the expected values");
    }

    private void assertMetricIndexMatches(String tenantId, MetricType type, List<? extends Metric> expected)
        throws Exception {
        ListenableFuture<List<Metric>> metricsFuture = metricsService.findMetrics(tenantId, type);
        List<Metric> actualIndex = getUninterruptibly(metricsFuture);

        assertEquals(actualIndex, expected, "The metrics index results do not match");
    }

    private static class VerifyTTLDataAccess implements DataAccess {

        private DataAccess instance;

        private int numericTTL;

        private int numericTagTTL;

        private int availabilityTTL;

        private int availabilityTagTTL;

        public VerifyTTLDataAccess(DataAccess instance) {
            this.instance = instance;
            numericTTL = MetricsServiceCassandra.DEFAULT_TTL;
            numericTagTTL = MetricsServiceCassandra.DEFAULT_TTL;
            availabilityTTL = MetricsServiceCassandra.DEFAULT_TTL;
            availabilityTagTTL = MetricsServiceCassandra.DEFAULT_TTL;
        }

        public void setNumericTTL(int expectedTTL) {
            this.numericTTL = expectedTTL;
        }

        public void setNumericTagTTL(int numericTagTTL) {
            this.numericTagTTL = numericTagTTL;
        }

        public void setAvailabilityTTL(int availabilityTTL) {
            this.availabilityTTL = availabilityTTL;
        }

        public void setAvailabilityTagTTL(int availabilityTagTTL) {
            this.availabilityTagTTL = availabilityTagTTL;
        }

        @Override
        public ResultSetFuture insertData(NumericMetric2 metric, int ttl) {
            assertEquals(ttl, numericTTL, "The numeric data TTL does not match the expected value when " +
                "inserting data");
            return instance.insertData(metric, ttl);
        }

        @Override
        public ResultSetFuture insertData(AvailabilityMetric metric, int ttl) {
            assertEquals(ttl, availabilityTTL, "The availability data TTL does not match the expected value when " +
                "inserting data");
            return instance.insertData(metric, ttl);
        }

        @Override
        public ResultSetFuture insertNumericTag(String tag, List<NumericData> data) {
            for (NumericData d : data) {
                assertTrue(d.getTTL() <= numericTagTTL, "Expected the TTL to be <= " + numericTagTTL +
                    " when tagging numeric data");
            }
            return instance.insertNumericTag(tag, data);
        }

        @Override
        public ResultSetFuture insertAvailabilityTag(String tag, List<Availability> data) {
            for (Availability a : data) {
                assertTrue(a.getTTL() <= availabilityTagTTL, "Expected the TTL to be <= " + availabilityTagTTL +
                    " when tagging availability data");
            }
            return instance.insertAvailabilityTag(tag, data);
        }

        @Override
        public ResultSetFuture insertTenant(Tenant tenant) {
            return instance.insertTenant(tenant);
        }

        @Override
        public ResultSetFuture findAllTenantIds() {
            return instance.findAllTenantIds();
        }

        @Override
        public ResultSetFuture findTenant(String id) {
            return instance.findTenant(id);
        }

        @Override
        public ResultSetFuture insertMetric(Metric metric) {
            return instance.insertMetric(metric);
        }

        @Override
        public ResultSetFuture findMetric(String tenantId, MetricType type, MetricId id, long dpart) {
            return instance.findMetric(tenantId, type, id, dpart);
        }

        @Override
        public ResultSetFuture addMetadata(Metric metric) {
            return null;
        }

        @Override
        public ResultSetFuture updateMetadata(Metric metric, Map<String, String> additions, Set<String> removals) {
            return instance.updateMetadata(metric, additions, removals);
        }

        @Override
        public ResultSetFuture updateMetadataInMetricsIndex(Metric metric, Map<String, String> additions,
            Set<String> deletions) {
            return instance.updateMetadataInMetricsIndex(metric, additions, deletions);
        }

        @Override
        public <T extends Metric> ResultSetFuture updateMetricsIndex(List<T> metrics) {
            return instance.updateMetricsIndex(metrics);
        }

        @Override
        public ResultSetFuture findMetricsInMetricsIndex(String tenantId, MetricType type) {
            return null;
        }

        @Override
        public ResultSetFuture findData(NumericMetric2 metric, long startTime, long endTime) {
            return instance.findData(metric, startTime, endTime);
        }

        @Override
        public ResultSetFuture findData(NumericMetric2 metric, long startTime, long endTime, boolean includeWriteTime) {
            return instance.findData(metric, startTime,endTime, includeWriteTime);
        }

        @Override
        public ResultSetFuture findData(NumericMetric2 metric, long timestamp, boolean includeWriteTime) {
            return instance.findData(metric, timestamp, includeWriteTime);
        }

        @Override
        public ResultSetFuture findData(AvailabilityMetric metric, long startTime, long endTime) {
            return instance.findData(metric, startTime, endTime);
        }

        @Override
        public ResultSetFuture findData(AvailabilityMetric metric, long startTime, long endTime,
            boolean includeWriteTime) {
            return instance.findData(metric, startTime, endTime, includeWriteTime);
        }

        @Override
        public ResultSetFuture findData(AvailabilityMetric metric, long timestamp) {
            return instance.findData(metric, timestamp);
        }

        @Override
        public ResultSetFuture deleteNumericMetric(String tenantId, String metric, Interval interval, long dpart) {
            return instance.deleteNumericMetric(tenantId, metric, interval, dpart);
        }

        @Override
        public ResultSetFuture findAllNumericMetrics() {
            return instance.findAllNumericMetrics();
        }

        @Override
        public ResultSetFuture updateDataWithTag(MetricData data, Set<String> tags) {
            return instance.updateDataWithTag(data, tags);
        }

        @Override
        public ResultSetFuture findNumericDataByTag(String tenantId, String tag) {
            return instance.findNumericDataByTag(tenantId, tag);
        }

        @Override
        public ResultSetFuture findAvailabilityByTag(String tenantId, String tag) {
            return instance.findAvailabilityByTag(tenantId, tag);
        }

        @Override
        public ResultSetFuture findAvailabilityData(AvailabilityMetric metric, long startTime, long endTime) {
            return instance.findAvailabilityData(metric, startTime, endTime);
        }

        @Override
        public ResultSetFuture updateCounter(Counter counter) {
            return instance.updateCounter(counter);
        }

        @Override
        public ResultSetFuture updateCounters(Collection<Counter> counters) {
            return instance.updateCounters(counters);
        }
    }

}
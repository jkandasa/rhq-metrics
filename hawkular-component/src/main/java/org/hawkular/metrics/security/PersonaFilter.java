/*
 * Copyright 2014-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hawkular.metrics.security;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;

import java.io.IOException;

import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import org.hawkular.accounts.api.model.Persona;
import org.hawkular.metrics.model.ApiError;

/**
 * When metrics is deployed in the full Hawkular server, the value of the tenant header is determined by the current
 * Persona which is injected from PersonaService. The person is only injected when the request supplies valid
 * credentials. This filter will not execute when the request has invalid credentials or no credentials at all. In
 * the former case a 401 status code is included in the response. In the latter case, a 500 status code is included
 * in the response. See AuthenticationITest.groovy for examples.
 *
 * @author jsanda
 */
@Provider
@PreMatching
public class PersonaFilter implements ContainerRequestFilter {
    private static final String TENANT_HEADER_NAME = "Hawkular-Tenant";
    private static final String TENANT_HEADER_NOT_ALLOWED = "The " + TENANT_HEADER_NAME + " header is not allowed. " +
            "The tenant is determined from the credentials supplied with the request";

    @Inject
    Instance<Persona> personaInstance;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();
        if (path.equals("/status") || path.equals("/")) {
            return;
        }

        if (requestContext.getHeaderString(TENANT_HEADER_NAME) != null) {
            requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .type(APPLICATION_JSON_TYPE)
                    .entity(new ApiError(TENANT_HEADER_NOT_ALLOWED))
                    .build());
            return;
        }

        Persona persona = personaInstance.get();
        if (persona == null) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
        } else {
            requestContext.getHeaders().putSingle(TENANT_HEADER_NAME, persona.getIdAsUUID().toString());
        }
    }
}

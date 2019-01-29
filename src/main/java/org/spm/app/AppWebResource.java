/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spm.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onosproject.rest.AbstractWebResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;

/**
 * Sample web resource.
 */
@Path("spm")
public class AppWebResource extends AbstractWebResource {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Create Intent.
     *
     * @return 200 OK
     */

    @POST
    @Path("provision/host")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response provisionedHost(InputStream inputStream) {

        try {

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            IntentSPM intentSPM = mapper.readValue(inputStream, IntentSPM.class);
            ServiceSPM serviceSPM = get(ServiceSPM.class);
            serviceSPM.provisionedHost(intentSPM);

        } catch (Exception ex) {

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).
                    entity(ex.toString())
                    .build();
        }

        ObjectNode node = mapper().createObjectNode();
        node.put("ApplicationId", "org.spm.app");
        node.put("Host", "Host Provisioned Successfully.");
        node.put("Intent", "Proactive Load Balancing Intent Created Successfully.");
        return ok(node).build();
    }
}

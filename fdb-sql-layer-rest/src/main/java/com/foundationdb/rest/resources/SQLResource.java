/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.rest.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundationdb.rest.ResourceRequirements;
import com.foundationdb.rest.RestResponseBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;

import static com.foundationdb.rest.resources.ResourceHelper.MEDIATYPE_JSON_JAVASCRIPT;

@Path("/sql")
public class SQLResource {
    private static final Logger logger = LoggerFactory.getLogger(SQLResource.class);

    private final ResourceRequirements reqs;

    public SQLResource(ResourceRequirements reqs) {
        this.reqs = reqs;
    }

    /** Run a single SQL statement specified by the 'q' query parameter. */
    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MEDIATYPE_JSON_JAVASCRIPT)
    public Response query(@Context final HttpServletRequest request,
                          final String jsonParams) {
        logger.debug("/sql/query: {}", jsonParams);
        return RestResponseBuilder
                .forRequest(request)
                .body(new RestResponseBuilder.BodyGenerator() {
                    @Override
                    public void write(PrintWriter writer) throws Exception {
                        Map<String, String> paramMap = new ObjectMapper().readValue(jsonParams, 
                                                                                    new TypeReference<Map<String, String>>() {});
                        reqs.restDMLService.runSQL(writer, request, paramMap.get("q"), null);
                    }
                })
                .build();
    }

    /** Explain a single SQL statement specified by the 'q' query parameter. */
    @POST
    @Path("/explain")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MEDIATYPE_JSON_JAVASCRIPT)
    public Response explain(@Context final HttpServletRequest request,
                            final String jsonParams) {
        logger.debug("/sql/explain: {}", jsonParams);
        return RestResponseBuilder
                .forRequest(request)
                .body(new RestResponseBuilder.BodyGenerator() {
                    @Override
                    public void write(PrintWriter writer) throws Exception {
                        Map<String, String> paramMap = new ObjectMapper().readValue(jsonParams, 
                                                                                    new TypeReference<Map<String, String>>() {});
                        reqs.restDMLService.explainSQL(writer, request, paramMap.get("q"));
                    }
                })
                .build();
    }

    /** Run multiple SQL statements (single transaction) specified by semi-colon separated strings in the POST body. */
    @POST
    @Path("/execute")
    @Produces(MEDIATYPE_JSON_JAVASCRIPT)
    public Response execute(@Context final HttpServletRequest request,
                            final byte[] postBytes) {
        String input = new String(postBytes);
        logger.debug("/sql/execute: {}", input);
        final String[] statements = input.split(";");
        return RestResponseBuilder
                .forRequest(request)
                .body(new RestResponseBuilder.BodyGenerator() {
                    @Override
                    public void write(PrintWriter writer) throws Exception {
                        reqs.restDMLService.runSQL(writer, request, Arrays.asList(statements));
                    }
                })
                .build();
    }
}

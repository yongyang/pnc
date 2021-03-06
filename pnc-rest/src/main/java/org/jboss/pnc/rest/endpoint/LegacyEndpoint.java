package org.jboss.pnc.rest.endpoint;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;

import org.jboss.pnc.core.exception.CoreException;
import org.jboss.pnc.rest.provider.BuildConfigurationProvider;
import org.jboss.pnc.rest.restmodel.BuildConfigurationRest;
import org.jboss.pnc.rest.trigger.BuildTriggerer;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.List;

@Api(value = "/configuration", description = "Legacy endpoint - please use the new one (starts with Product)")
@Path("/configuration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LegacyEndpoint {

    private BuildConfigurationProvider buildConfigurationProvider;
    private BuildTriggerer buildTriggerer;

    public LegacyEndpoint() {
    }

    @Inject
    public LegacyEndpoint(BuildConfigurationProvider buildConfigurationProvider, BuildTriggerer buildTriggerer) {
        this.buildConfigurationProvider = buildConfigurationProvider;
        this.buildTriggerer = buildTriggerer;
    }

    @ApiOperation(value = "Gets all Product Configuration")
    @GET
    public Response getAll(@ApiParam(value = "Page index", required = false) @QueryParam("pageIndex") Integer pageIndex,
            @ApiParam(value = "Pagination size", required = false) @QueryParam("pageSize") Integer pageSize,
            @ApiParam(value = "Sorting field", required = false) @QueryParam("sorted_by") String field,
            @ApiParam(value = "Sort direction", required = false) @QueryParam("sorting") String sorting) {

        return Response.ok(buildConfigurationProvider.getAll(pageIndex, pageSize, field, sorting)).build();
    }

    @ApiOperation(value = "Triggers a build")
    @POST
    @Path("/{id}/build")
    public Response build(@ApiParam(value = "Project's Configuration id", required = true) @PathParam("id") Integer id,
            @Context UriInfo uriInfo) {
        try {
            Integer runningBuildId = buildTriggerer.triggerBuilds(id);
            UriBuilder uriBuilder = UriBuilder.fromUri(uriInfo.getBaseUri()).path("/result/running/{id}");
            URI uri = uriBuilder.build(runningBuildId);
            return Response.created(uri).entity(uri).build();
        } catch (CoreException e) {
            return Response.serverError().entity("Core error: " + e.getMessage()).build();
        } catch (Exception e) {
            return Response.serverError().entity("Other error: " + e.getMessage()).build();
        }
    }
}

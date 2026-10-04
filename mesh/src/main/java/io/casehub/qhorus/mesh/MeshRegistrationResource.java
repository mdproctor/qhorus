package io.casehub.qhorus.mesh;

import io.casehub.qhorus.runtime.instance.InstanceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/instances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MeshRegistrationResource {

    @Inject InstanceService instanceService;

    public record RegisterRequest(String instanceId, String description,
                                   Map<String, String> metadata) {}

    @POST
    public Response register(RegisterRequest request) {
        List<String> capabilities = request.metadata != null
                ? request.metadata.values().stream().toList()
                : List.of();
        var instance = instanceService.register(
                request.instanceId, request.description,
                capabilities, null, false, request.metadata);
        return Response.ok(new MeshRegistration(
                instance.id(), instance.instanceId(), instance.description())).build();
    }

    @DELETE
    @Path("/{instanceId}")
    public Response deregister(@PathParam("instanceId") String instanceId) {
        instanceService.deregister(instanceId);
        return Response.noContent().build();
    }
}

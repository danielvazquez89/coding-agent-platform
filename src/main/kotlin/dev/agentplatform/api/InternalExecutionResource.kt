package dev.agentplatform.api

import dev.agentplatform.domain.model.ValidationCheck
import dev.agentplatform.domain.port.ValidationRunner
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.Path as FilePath
import java.security.MessageDigest

@Path("/internal/execution")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class InternalExecutionResource(
    private val validationRunner: ValidationRunner,
    @ConfigProperty(name = "agent.execution.control-plane-token", defaultValue = "")
    private val controlPlaneToken: String,
    @ConfigProperty(name = "agent.execution.workspace-root", defaultValue = "")
    private val workspaceRoot: String
) {
    data class ValidationRequest(val workspacePath: String)

    @POST
    @Path("/validate")
    fun validate(
        @HeaderParam("Authorization") authorization: String?,
        request: ValidationRequest
    ): ValidationCheck {
        authorize(authorization)
        val path = authorizedWorkspace(request.workspacePath)
        return validationRunner.validate(path)
    }

    private fun authorizedWorkspace(path: String): FilePath {
        val root = FilePath.of(workspaceRoot).toAbsolutePath().normalize()
        val realRoot = try {
            root.toRealPath()
        } catch (_: Exception) {
            throw WebApplicationException("Configured workspace root is unavailable", Response.Status.SERVICE_UNAVAILABLE)
        }
        val requested = try {
            FilePath.of(path).toAbsolutePath().normalize()
        } catch (_: Exception) {
            throw WebApplicationException("Workspace path is invalid", Response.Status.BAD_REQUEST)
        }
        val realPath = try {
            requested.toRealPath()
        } catch (_: Exception) {
            throw WebApplicationException("Workspace path does not exist", Response.Status.BAD_REQUEST)
        }
        if (realPath == realRoot || !realPath.startsWith(realRoot) || !Files.isDirectory(realPath)) {
            throw WebApplicationException("Workspace path is outside the authorized workspace root", Response.Status.FORBIDDEN)
        }
        return realPath
    }

    private fun authorize(authorization: String?) {
        if (controlPlaneToken.isBlank()) {
            throw WebApplicationException("Internal execution gateway is not configured", Response.Status.SERVICE_UNAVAILABLE)
        }
        val expected = "Bearer $controlPlaneToken".toByteArray(Charsets.UTF_8)
        val provided = authorization.orEmpty().toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expected, provided)) {
            throw WebApplicationException(Response.Status.UNAUTHORIZED)
        }
    }
}

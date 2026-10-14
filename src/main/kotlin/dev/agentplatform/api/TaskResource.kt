package dev.agentplatform.api

import dev.agentplatform.api.dto.ApiError
import dev.agentplatform.api.dto.CreateTaskRequest
import dev.agentplatform.api.dto.TaskAttemptResponse
import dev.agentplatform.api.dto.TaskStatusTransitionResponse
import dev.agentplatform.api.dto.TransitionTaskRequest
import dev.agentplatform.application.service.TaskAttemptService
import dev.agentplatform.application.service.TaskService
import dev.agentplatform.domain.model.CodingTask
import dev.agentplatform.domain.model.InvalidTaskTransitionException
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.net.URI
import java.util.UUID

@Path("/api/tasks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class TaskResource @Inject constructor(
    private val taskService: TaskService,
    private val attemptService: TaskAttemptService
) {

    @POST
    fun create(request: CreateTaskRequest): Response {
        if (request.title.isBlank()) throw BadRequestException("title must not be blank")
        if (request.description.isBlank()) throw BadRequestException("description must not be blank")

        val task = taskService.create(request)
        return Response.created(URI.create("/api/tasks/${task.id}")).entity(task).build()
    }

    @GET
    fun list(): List<CodingTask> = taskService.findAll()

    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: UUID): CodingTask =
        taskService.findById(id) ?: throw NotFoundException("Task $id was not found")

    @PATCH
    @Path("/{id}/status")
    fun transition(@PathParam("id") id: UUID, request: TransitionTaskRequest): CodingTask {
        if (request.reason != null && request.reason.length > 2000) {
            throw BadRequestException("reason must be 2000 characters or fewer")
        }
        return taskService.transition(id, request.status, request.reason)
            ?: throw NotFoundException("Task $id was not found")
    }

    @GET
    @Path("/{id}/history")
    fun history(@PathParam("id") id: UUID): List<TaskStatusTransitionResponse> {
        if (taskService.findById(id) == null) throw NotFoundException("Task $id was not found")
        return taskService.history(id)
    }

    @GET
    @Path("/{id}/attempts")
    fun attempts(@PathParam("id") id: UUID): List<TaskAttemptResponse> {
        if (taskService.findById(id) == null) throw NotFoundException("Task $id was not found")
        return attemptService.list(id)
    }

    @GET
    @Path("/{id}/attempts/{attemptId}")
    fun attempt(
        @PathParam("id") id: UUID,
        @PathParam("attemptId") attemptId: UUID
    ): TaskAttemptResponse = attemptService.find(id, attemptId)
        ?: throw NotFoundException("Attempt $attemptId was not found for task $id")
}

@Provider
class InvalidTaskTransitionMapper : ExceptionMapper<InvalidTaskTransitionException> {
    override fun toResponse(exception: InvalidTaskTransitionException): Response =
        Response.status(Response.Status.CONFLICT).entity(ApiError(exception.apiMessage)).build()
}

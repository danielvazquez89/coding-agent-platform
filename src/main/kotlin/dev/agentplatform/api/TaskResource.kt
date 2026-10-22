package dev.agentplatform.api

import dev.agentplatform.api.dto.*
import dev.agentplatform.application.service.TaskAttemptService
import dev.agentplatform.application.service.TaskService
import dev.agentplatform.domain.model.CodingTask
import dev.agentplatform.domain.model.InvalidTaskTransitionException
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.net.URI
import java.util.*

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
        if (request.acceptanceCriteria.size > MAX_ACCEPTANCE_CRITERIA) {
            throw BadRequestException("acceptanceCriteria must contain $MAX_ACCEPTANCE_CRITERIA items or fewer")
        }
        if (request.acceptanceCriteria.any { it.trim().length > MAX_ACCEPTANCE_CRITERION_LENGTH }) {
            throw BadRequestException("acceptanceCriteria items must be $MAX_ACCEPTANCE_CRITERION_LENGTH characters or fewer")
        }

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

    @POST
    @Path("/{id}/answer")
    fun answer(@PathParam("id") id: UUID, request: AnswerTaskRequest): CodingTask {
        if (request.answer.isBlank()) throw BadRequestException("answer must not be blank")
        if (request.answer.trim().length > MAX_ANSWER_LENGTH) {
            throw BadRequestException("answer must be $MAX_ANSWER_LENGTH characters or fewer")
        }
        return try {
            taskService.answerAndRequeue(id, request.answer)
                ?: throw NotFoundException("Task $id was not found")
        } catch (failure: IllegalStateException) {
            throw ClientErrorException(failure.message, Response.Status.CONFLICT)
        } catch (failure: IllegalArgumentException) {
            throw BadRequestException(failure.message)
        }
    }

    @POST
    @Path("/{id}/retry")
    @Consumes(MediaType.WILDCARD)
    fun retry(@PathParam("id") id: UUID): Response {
        val task = try {
            taskService.retry(id) ?: throw NotFoundException("Task $id was not found")
        } catch (failure: IllegalStateException) {
            return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiError(failure.message ?: "Task $id cannot be retried"))
                .build()
        }
        return Response.created(URI.create("/api/tasks/${task.id}")).entity(task).build()
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

    /**
     * Completes an attempt that ended NEEDS_INPUT after a human reviewed its diff and validation and
     * explicitly confirmed the criteria the model left unverified. The server still enforces the
     * machine checks (changed files, all validation passed, a complete diff, no failed criterion and
     * no blocking model question); this endpoint records the human decision, it does not bypass them.
     */
    @POST
    @Path("/{id}/attempts/{attemptId}/confirm")
    fun confirm(
        @PathParam("id") id: UUID,
        @PathParam("attemptId") attemptId: UUID,
        request: ConfirmAttemptRequest
    ): Response {
        if ((request.reviewer?.trim()?.length ?: 0) > MAX_REVIEWER_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiError("reviewer must be $MAX_REVIEWER_LENGTH characters or fewer"))
                .build()
        }
        return try {
            val attempt = attemptService.confirmAcceptance(id, attemptId, request.evidence, request.reviewer)
                ?: throw NotFoundException("Attempt $attemptId was not found for task $id")
            Response.ok(attempt).build()
        } catch (failure: IllegalArgumentException) {
            Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiError(failure.message ?: "The confirmation request is invalid"))
                .build()
        } catch (failure: IllegalStateException) {
            Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiError(failure.message ?: "Attempt $attemptId cannot be confirmed"))
                .build()
        }
    }

    @GET
    @Path("/{id}/attempts/{attemptId}/patch")
    @Produces(PATCH_MEDIA_TYPE)
    fun patch(
        @PathParam("id") id: UUID,
        @PathParam("attemptId") attemptId: UUID
    ): Response {
        if (taskService.findById(id) == null) throw NotFoundException("Task $id was not found")
        val diff = try {
            attemptService.exportablePatch(id, attemptId)
        } catch (failure: IllegalStateException) {
            return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiError(failure.message ?: "The patch for attempt $attemptId cannot be exported"))
                .build()
        }
        if (diff == null) throw NotFoundException("Attempt $attemptId was not found for task $id")
        return Response.ok(diff).type(PATCH_MEDIA_TYPE).build()
    }

    @GET
    @Path("/{id}/attempts/{attemptId}/tool-executions")
    fun toolExecutions(
        @PathParam("id") id: UUID,
        @PathParam("attemptId") attemptId: UUID
    ): List<dev.agentplatform.domain.model.ToolExecutionAudit> {
        if (taskService.findById(id) == null) throw NotFoundException("Task $id was not found")
        if (attemptService.find(id, attemptId) == null) {
            throw NotFoundException("Attempt $attemptId was not found for task $id")
        }
        return attemptService.toolExecutions(id, attemptId)
    }

    private companion object {
        const val MAX_ACCEPTANCE_CRITERIA = 30
        const val MAX_ACCEPTANCE_CRITERION_LENGTH = 1000
        const val MAX_ANSWER_LENGTH = 5000
        const val MAX_REVIEWER_LENGTH = 100
        const val PATCH_MEDIA_TYPE = "text/x-patch"
    }
}

@Provider
class InvalidTaskTransitionMapper : ExceptionMapper<InvalidTaskTransitionException> {
    override fun toResponse(exception: InvalidTaskTransitionException): Response =
        Response.status(Response.Status.CONFLICT).entity(ApiError(exception.apiMessage)).build()
}

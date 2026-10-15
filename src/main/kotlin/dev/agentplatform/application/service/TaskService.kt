package dev.agentplatform.application.service

import dev.agentplatform.api.dto.CreateTaskRequest
import dev.agentplatform.api.dto.TaskStatusTransitionResponse
import dev.agentplatform.domain.model.CodingTask
import dev.agentplatform.domain.model.InvalidTaskTransitionException
import dev.agentplatform.domain.model.TaskLifecycle
import dev.agentplatform.domain.model.TaskStatus
import dev.agentplatform.domain.model.TaskStatusTransition
import dev.agentplatform.domain.port.TaskExecutionState
import dev.agentplatform.infrastructure.persistence.TaskRepository
import dev.agentplatform.infrastructure.persistence.TaskStatusTransitionRepository
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.*

@ApplicationScoped
class TaskService(
    private val taskRepository: TaskRepository,
    private val transitionRepository: TaskStatusTransitionRepository
) : TaskExecutionState {

    @Transactional
    override fun claimNextQueued(): CodingTask? {
        val task = taskRepository.find("status", Sort.ascending("createdAt"), TaskStatus.QUEUED)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult() ?: return null
        markRunning(task)
        return task
    }

    @Transactional
    override fun claim(id: UUID): CodingTask? {
        val task = taskRepository.findForUpdate(id) ?: return null
        if (task.status != TaskStatus.QUEUED) return null
        markRunning(task)
        return task
    }

    @Transactional
    fun create(request: CreateTaskRequest): CodingTask {
        val task = CodingTask(
            id = UUID.randomUUID(),
            title = request.title.trim(),
            description = request.description.trim(),
            acceptanceCriteria = request.acceptanceCriteria.map(String::trim).filter(String::isNotEmpty)
                .toMutableList(),
            repositoryUrl = request.repositoryUrl?.trim()?.takeIf(String::isNotEmpty),
            status = TaskStatus.QUEUED,
            createdAt = Instant.now()
        )
        taskRepository.persist(task)
        transitionRepository.persist(
            TaskStatusTransition(
                taskId = task.id,
                sequenceNumber = 1,
                fromStatus = null,
                toStatus = task.status,
                actor = "API"
            )
        )
        return task
    }

    @Transactional
    fun answerAndRequeue(id: UUID, answer: String): CodingTask? {
        val normalizedAnswer = answer.trim()
        require(normalizedAnswer.isNotEmpty()) { "answer must not be blank" }
        require(normalizedAnswer.length <= MAX_ANSWER_LENGTH) {
            "answer must be $MAX_ANSWER_LENGTH characters or fewer"
        }

        val task = taskRepository.findForUpdate(id) ?: return null
        check(task.status == TaskStatus.NEEDS_INPUT) {
            "Task $id can only be answered while it needs input"
        }
        require(task.description.length + normalizedAnswer.length + CLARIFICATION_MARKER.length <= MAX_DESCRIPTION_LENGTH) {
            "The task and clarification exceed the $MAX_DESCRIPTION_LENGTH character limit"
        }

        task.description = "$CLARIFICATION_MARKER$normalizedAnswer\n\n${task.description}"
        return recordTransition(
            task, TaskStatus.QUEUED, "User provided clarification; task requeued", "API",
            allowNeedsInputResume = true
        )
    }

    fun findAll(): List<CodingTask> = taskRepository.listNewestFirst()

    override fun findById(id: UUID): CodingTask? = taskRepository.findById(id)

    @Transactional
    override fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String): CodingTask? {
        val task = taskRepository.findForUpdate(id) ?: return null
        return recordTransition(task, target, reason, actor)
    }

    private fun recordTransition(
        task: CodingTask,
        target: TaskStatus,
        reason: String?,
        actor: String,
        allowNeedsInputResume: Boolean = false
    ): CodingTask {
        if (!allowNeedsInputResume && task.status == TaskStatus.NEEDS_INPUT && target == TaskStatus.QUEUED) {
            throw InvalidTaskTransitionException(task.status, target)
        }
        TaskLifecycle.requireAllowed(task.status, target)

        val previousStatus = task.status
        task.status = target
        transitionRepository.persist(
            TaskStatusTransition(
                taskId = task.id,
                sequenceNumber = transitionRepository.nextSequenceNumber(task.id),
                fromStatus = previousStatus,
                toStatus = target,
                reason = reason?.trim()?.takeIf(String::isNotEmpty),
                actor = actor
            )
        )
        return task
    }

    private fun markRunning(task: CodingTask) {
        task.status = TaskStatus.RUNNING
        transitionRepository.persist(
            TaskStatusTransition(
                taskId = task.id,
                sequenceNumber = transitionRepository.nextSequenceNumber(task.id),
                fromStatus = TaskStatus.QUEUED,
                toStatus = TaskStatus.RUNNING,
                reason = "Claimed by execution worker",
                actor = "WORKER"
            )
        )
    }

    fun history(id: UUID): List<TaskStatusTransitionResponse> =
        transitionRepository.forTaskInOrder(id).map {
            TaskStatusTransitionResponse(
                sequenceNumber = it.sequenceNumber,
                taskId = it.taskId,
                fromStatus = it.fromStatus,
                toStatus = it.toStatus,
                reason = it.reason,
                actor = it.actor,
                transitionedAt = it.transitionedAt
            )
        }

    private companion object {
        const val MAX_ANSWER_LENGTH = 5000
        const val MAX_DESCRIPTION_LENGTH = 50_000
        const val CLARIFICATION_MARKER = "\n\nUser clarification:\n"
    }
}

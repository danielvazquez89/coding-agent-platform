package dev.agentplatform

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class TaskService(
    private val taskRepository: TaskRepository,
    private val transitionRepository: TaskStatusTransitionRepository
) {

    @Transactional
    fun create(request: CreateTaskRequest): CodingTask {
        val task = CodingTask(
            id = UUID.randomUUID(),
            title = request.title.trim(),
            description = request.description.trim(),
            acceptanceCriteria = request.acceptanceCriteria.map(String::trim).filter(String::isNotEmpty).toMutableList(),
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

    fun findAll(): List<CodingTask> = taskRepository.listNewestFirst()

    fun findById(id: UUID): CodingTask? = taskRepository.findById(id)

    @Transactional
    fun transition(id: UUID, target: TaskStatus, reason: String?, actor: String = "API"): CodingTask? {
        val task = taskRepository.findForUpdate(id) ?: return null
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
}

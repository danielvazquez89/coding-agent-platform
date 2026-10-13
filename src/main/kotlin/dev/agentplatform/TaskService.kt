package dev.agentplatform

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class TaskService(private val taskRepository: TaskRepository) {

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
        return task
    }

    fun findAll(): List<CodingTask> = taskRepository.listNewestFirst()

    fun findById(id: UUID): CodingTask? = taskRepository.findById(id)
}

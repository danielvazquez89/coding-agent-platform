package dev.agentplatform.infrastructure.persistence

import dev.agentplatform.domain.model.TaskStatusTransition
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class TaskStatusTransitionRepository : PanacheRepositoryBase<TaskStatusTransition, UUID> {
    fun forTaskInOrder(taskId: UUID): List<TaskStatusTransition> =
        find("taskId", Sort.ascending("sequenceNumber"), taskId).list()

    fun nextSequenceNumber(taskId: UUID): Int = count("taskId", taskId).toInt() + 1
}

package dev.agentplatform.infrastructure.persistence

import dev.agentplatform.domain.model.AttemptStatus
import dev.agentplatform.domain.model.TaskAttempt
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.util.UUID

@ApplicationScoped
class TaskAttemptRepository : PanacheRepositoryBase<TaskAttempt, UUID> {
    fun forTaskInOrder(taskId: UUID): List<TaskAttempt> =
        find("taskId", Sort.ascending("attemptNumber"), taskId).list()

    fun nextAttemptNumber(taskId: UUID): Int = count("taskId", taskId).toInt() + 1

    fun hasRunningAttempt(taskId: UUID): Boolean = count("taskId = ?1 and status = ?2", taskId, AttemptStatus.RUNNING) > 0

    fun findForUpdate(id: UUID): TaskAttempt? = findById(id, LockModeType.PESSIMISTIC_WRITE)
}

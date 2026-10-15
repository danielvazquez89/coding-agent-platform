package dev.agentplatform.infrastructure.persistence

import dev.agentplatform.domain.model.ToolExecutionEvent
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.util.UUID

@ApplicationScoped
class ToolExecutionEventRepository : PanacheRepositoryBase<ToolExecutionEvent, UUID> {
    fun forAttemptInOrder(taskId: UUID, attemptId: UUID): List<ToolExecutionEvent> =
        find("taskId = ?1 and attemptId = ?2", Sort.ascending("sequenceNumber"), taskId, attemptId).list()

    fun nextSequenceNumber(attemptId: UUID): Int = count("attemptId", attemptId).toInt() + 1

    fun findForAttemptSequence(attemptId: UUID, sequenceNumber: Int): ToolExecutionEvent? =
        find("attemptId = ?1 and sequenceNumber = ?2", attemptId, sequenceNumber)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult()
}

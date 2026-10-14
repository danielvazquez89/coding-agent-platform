package dev.agentplatform.infrastructure.persistence

import dev.agentplatform.domain.model.CodingTask
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.util.UUID

@ApplicationScoped
class TaskRepository : PanacheRepositoryBase<CodingTask, UUID> {
    fun listNewestFirst(): List<CodingTask> = findAll(Sort.descending("createdAt")).list()

    fun findForUpdate(id: UUID): CodingTask? = findById(id, LockModeType.PESSIMISTIC_WRITE)
}

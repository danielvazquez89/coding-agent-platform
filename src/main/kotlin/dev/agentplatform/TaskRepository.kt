package dev.agentplatform

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class TaskRepository : PanacheRepositoryBase<CodingTask, UUID> {
    fun listNewestFirst(): List<CodingTask> = findAll(Sort.descending("createdAt")).list()
}

package dev.agentplatform.domain.model

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "coding_tasks")
class CodingTask(
    @field:Id
    var id: UUID = UUID.randomUUID(),

    @field:Column(nullable = false, length = 200)
    var title: String = "",

    @field:Column(nullable = false, columnDefinition = "text")
    var description: String = "",

    @field:ElementCollection(fetch = FetchType.EAGER)
    @field:CollectionTable(name = "task_acceptance_criteria", joinColumns = [JoinColumn(name = "task_id")])
    @field:OrderColumn(name = "position")
    @field:Column(name = "criterion", nullable = false, columnDefinition = "text")
    var acceptanceCriteria: MutableList<String> = mutableListOf(),

    @field:Column(name = "repository_url", length = 2048)
    var repositoryUrl: String? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 32)
    var status: TaskStatus = TaskStatus.QUEUED,

    @field:Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

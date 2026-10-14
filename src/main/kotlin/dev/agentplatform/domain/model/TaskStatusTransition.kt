package dev.agentplatform.domain.model

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "task_status_transitions")
class TaskStatusTransition(
    @field:Id
    var id: UUID = UUID.randomUUID(),

    @field:Column(name = "task_id", nullable = false)
    var taskId: UUID = UUID.randomUUID(),

    @field:Column(name = "sequence_number", nullable = false)
    var sequenceNumber: Int = 1,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "from_status", length = 32)
    var fromStatus: TaskStatus? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "to_status", nullable = false, length = 32)
    var toStatus: TaskStatus = TaskStatus.QUEUED,

    @field:Column(columnDefinition = "text")
    var reason: String? = null,

    @field:Column(nullable = false, length = 64)
    var actor: String = "API",

    @field:Column(name = "transitioned_at", nullable = false)
    var transitionedAt: Instant = Instant.now()
)

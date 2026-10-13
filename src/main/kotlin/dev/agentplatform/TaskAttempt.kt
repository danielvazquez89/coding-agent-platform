package dev.agentplatform

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class AttemptStatus { RUNNING, COMPLETED, FAILED, NEEDS_INPUT }

@Entity
@Table(name = "task_attempts")
class TaskAttempt(
    @field:Id
    var id: UUID = UUID.randomUUID(),

    @field:Column(name = "task_id", nullable = false)
    var taskId: UUID = UUID.randomUUID(),

    @field:Column(name = "attempt_number", nullable = false)
    var attemptNumber: Int = 1,

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 32)
    var status: AttemptStatus = AttemptStatus.RUNNING,

    @field:Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @field:Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @field:Column(name = "error_summary", columnDefinition = "text")
    var errorSummary: String? = null,

    @field:Column(name = "report_json", columnDefinition = "text")
    var reportJson: String? = null
)

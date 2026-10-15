package dev.agentplatform.domain.model

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "tool_execution_events",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_tool_execution_events_attempt_sequence",
        columnNames = ["attempt_id", "sequence_number"]
    )]
)
class ToolExecutionEvent(
    @field:Id
    var id: UUID = UUID.randomUUID(),

    @field:Column(name = "task_id", nullable = false)
    var taskId: UUID = UUID.randomUUID(),

    @field:Column(name = "attempt_id", nullable = false)
    var attemptId: UUID = UUID.randomUUID(),

    @field:Column(name = "sequence_number", nullable = false)
    var sequenceNumber: Int = 1,

    @field:Column(name = "tool_name", nullable = false, length = 100)
    var toolName: String = "",

    @field:Column(name = "arguments_summary", nullable = false, length = 500)
    var argumentsSummary: String = "",

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 16)
    var outcome: ToolExecutionOutcome = ToolExecutionOutcome.SUCCEEDED,

    @field:Column(name = "duration_millis", nullable = false)
    var durationMillis: Long = 0,

    @field:Column(name = "result_summary", nullable = false, length = 300)
    var resultSummary: String = "",

    @field:Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant = Instant.now()
)

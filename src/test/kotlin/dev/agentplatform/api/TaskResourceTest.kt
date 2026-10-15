package dev.agentplatform.api

import dev.agentplatform.api.dto.CreateTaskRequest
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.*

@QuarkusTest
class TaskResourceTest {
    @Inject
    lateinit var taskResource: TaskResource

    @Test
    fun `review page is served with task and resume controls`() {
        given()
            .`when`().get("/")
            .then()
            .statusCode(200)
            .body(containsString("Coding Agent Review"))
            .body(containsString("Answer and resume"))
    }

    @Test
    fun `internal validation gateway rejects requests without its bearer token`() {
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("workspacePath" to "/tmp/not-authorized"))
            .`when`().post("/internal/execution/validate")
            .then()
            .statusCode(401)
    }

    @Test
    fun `internal validation gateway rejects workspaces outside its configured root`() {
        given()
            .header("Authorization", "Bearer test-only-internal-token")
            .contentType(ContentType.JSON)
            .body(mapOf("workspacePath" to ".."))
            .`when`().post("/internal/execution/validate")
            .then()
            .statusCode(403)
    }

    @Test
    fun `resource validation and direct lookup cover both branches`() {
        assertThrows(BadRequestException::class.java) {
            taskResource.create(CreateTaskRequest(" ", "Description"))
        }
        assertThrows(BadRequestException::class.java) {
            taskResource.create(CreateTaskRequest("Title", " "))
        }
        assertThrows(BadRequestException::class.java) {
            taskResource.create(CreateTaskRequest("Title", "Description", List(31) { "criterion $it" }))
        }
        assertThrows(BadRequestException::class.java) {
            taskResource.create(CreateTaskRequest("Title", "Description", listOf("x".repeat(1001))))
        }

        val created = taskResource.create(CreateTaskRequest("Title", "Description"))
        val id = UUID.fromString(created.location.path.substringAfterLast('/'))
        assertNotNull(taskResource.get(id))
        assertThrows(NotFoundException::class.java) { taskResource.get(UUID.randomUUID()) }
    }

    @Test
    fun `created task is queued and has an initial history entry`() {
        val id = createTask()

        given()
            .`when`().get("/api/tasks/$id")
            .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("title", equalTo("Lifecycle test task"))
            .body("status", equalTo("QUEUED"))
            .body("acceptanceCriteria", equalTo(listOf("First criterion", "Second criterion")))

        given()
            .`when`().get("/api/tasks")
            .then()
            .statusCode(200)
            .body("id", hasItem(id))

        given()
            .`when`().get("/api/tasks/$id/history")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].sequenceNumber", equalTo(1))
            .body("[0].fromStatus", nullValue())
            .body("[0].toStatus", equalTo("QUEUED"))
    }

    @Test
    fun `valid lifecycle transitions are persisted in order with reasons`() {
        val id = createTask()

        transition(id, "RUNNING", " Worker claimed task ")
            .body("status", equalTo("RUNNING"))
        transition(id, "VALIDATING", "Patch is ready")
            .body("status", equalTo("VALIDATING"))
        transition(id, "COMPLETED", "All checks passed")
            .body("status", equalTo("COMPLETED"))

        given()
            .`when`().get("/api/tasks/$id/history")
            .then()
            .statusCode(200)
            .body("size()", equalTo(4))
            .body("[0].toStatus", equalTo("QUEUED"))
            .body("[1].sequenceNumber", equalTo(2))
            .body("[1].fromStatus", equalTo("QUEUED"))
            .body("[1].toStatus", equalTo("RUNNING"))
            .body("[1].reason", equalTo("Worker claimed task"))
            .body("[2].fromStatus", equalTo("RUNNING"))
            .body("[2].toStatus", equalTo("VALIDATING"))
            .body("[3].fromStatus", equalTo("VALIDATING"))
            .body("[3].toStatus", equalTo("COMPLETED"))
            .body("[3].actor", equalTo("API"))
    }

    @Test
    fun `invalid and terminal transitions conflict without adding history`() {
        val id = createTask()

        transition(id, "COMPLETED").statusCode(409)
        transition(id, "RUNNING").statusCode(200)
        transition(id, "FAILED", "Worker failed").statusCode(200)
        transition(id, "COMPLETED").statusCode(409)

        given()
            .`when`().get("/api/tasks/$id/history")
            .then()
            .statusCode(200)
            .body("size()", equalTo(3))
            .body("[2].toStatus", equalTo("FAILED"))
    }

    @Test
    fun `unknown tasks return not found for status and history`() {
        val missingId = "00000000-0000-0000-0000-000000000001"

        given()
            .`when`().get("/api/tasks/$missingId")
            .then()
            .statusCode(404)

        transition(missingId, "RUNNING").statusCode(404)
        given()
            .`when`().get("/api/tasks/$missingId/history")
            .then()
            .statusCode(404)
    }

    @Test
    fun `creation rejects blank required fields`() {
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("title" to "   ", "description" to "Description"))
            .`when`().post("/api/tasks")
            .then()
            .statusCode(400)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("title" to "Title", "description" to "  "))
            .`when`().post("/api/tasks")
            .then()
            .statusCode(400)
    }

    @Test
    fun `oversized transition reason is rejected without changing task or history`() {
        val id = createTask()

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("status" to "RUNNING", "reason" to "x".repeat(2001)))
            .`when`().patch("/api/tasks/$id/status")
            .then()
            .statusCode(400)

        given()
            .`when`().get("/api/tasks/$id")
            .then()
            .statusCode(200)
            .body("status", equalTo("QUEUED"))

        given()
            .`when`().get("/api/tasks/$id/history")
            .then()
            .statusCode(200)
            .body("size()", equalTo(1))
    }

    @Test
    fun `answer endpoint resumes needs-input task and rejects invalid answers`() {
        val id = createTask()
        transition(id, "RUNNING")
        transition(id, "NEEDS_INPUT", "Clarification required")

        transition(id, "QUEUED").statusCode(409)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("answer" to "Use cursor pagination"))
            .`when`().post("/api/tasks/$id/answer")
            .then()
            .statusCode(200)
            .body("status", equalTo("QUEUED"))
            .body("description", org.hamcrest.Matchers.containsString("Use cursor pagination"))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("answer" to "answer"))
            .`when`().post("/api/tasks/$id/answer")
            .then()
            .statusCode(409)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("answer" to " "))
            .`when`().post("/api/tasks/$id/answer")
            .then()
            .statusCode(400)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("answer" to "x".repeat(5001)))
            .`when`().post("/api/tasks/$id/answer")
            .then()
            .statusCode(400)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("answer" to "answer"))
            .`when`().post("/api/tasks/00000000-0000-0000-0000-000000000001/answer")
            .then()
            .statusCode(404)

        val descriptionLimitTask = createTask("x".repeat(50_000))
        transition(descriptionLimitTask, "RUNNING")
        transition(descriptionLimitTask, "NEEDS_INPUT")
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("answer" to "additional context"))
            .`when`().post("/api/tasks/$descriptionLimitTask/answer")
            .then()
            .statusCode(400)
    }

    private fun createTask(description: String = "Exercise persisted task lifecycle behavior"): String =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "title" to "Lifecycle test task",
                    "description" to description,
                    "acceptanceCriteria" to listOf("First criterion", "Second criterion")
                )
            )
            .`when`().post("/api/tasks")
            .then()
            .statusCode(201)
            .extract().path("id")

    private fun transition(id: String, status: String, reason: String? = null) =
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("status" to status, "reason" to reason))
            .`when`().patch("/api/tasks/$id/status")
            .then()
}

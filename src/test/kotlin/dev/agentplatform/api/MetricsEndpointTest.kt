package dev.agentplatform.api

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.containsString
import org.junit.jupiter.api.Test

@QuarkusTest
class MetricsEndpointTest {
    @Test
    fun `exposes the Prometheus scrape endpoint`() {
        given()
            .`when`().get("/q/metrics")
            .then()
            .statusCode(200)
            .body(containsString("jvm_memory_used_bytes"))
    }
}

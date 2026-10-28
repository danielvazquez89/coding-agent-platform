package dev.agentplatform.infrastructure.docker

import dev.agentplatform.domain.model.CheckOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class SurefireReportsTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `reads passing failing error and skipped cases with durations`() {
        val reports = reportDirectory()
        Files.writeString(
            reports.resolve("TEST-example.GreetingTest.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="example.GreetingTest" tests="5">
              <testcase classname="example.GreetingTest" name="greetsNullNameAsGuest" time="0.125"/>
              <testcase classname="example.GreetingTest" name="rejectsBlank" time="0.5">
                <failure message="expected guest">Assertion failed</failure>
              </testcase>
              <testcase classname="example.GreetingTest" name="throwsOnBrokenInput" time="0.25">
                <error message="boom">Stack trace</error>
              </testcase>
              <testcase classname="example.GreetingTest" name="pending" time="not-a-number">
                <skipped/>
              </testcase>
              <testcase classname="" name="ignored" time="0.1"/>
              <testcase classname="example.GreetingTest" name="" time="0.1"/>
            </testsuite>
            """.trimIndent()
        )
        Files.writeString(
            reports.resolve("TEST-example.OtherTest.xml"),
            """
            <testsuite>
              <testcase classname="example.OtherTest" name="works" time="0.01"/>
            </testsuite>
            """.trimIndent()
        )
        Files.writeString(reports.resolve("not-a-report.txt"), "ignored")

        val results = SurefireReports.read(temporary)

        assertEquals(5, results.size)
        assertEquals(
            listOf(
                "example.GreetingTest/greetsNullNameAsGuest/PASSED",
                "example.GreetingTest/pending/SKIPPED",
                "example.GreetingTest/rejectsBlank/FAILED",
                "example.GreetingTest/throwsOnBrokenInput/FAILED",
                "example.OtherTest/works/PASSED"
            ),
            results.map { "${it.className}/${it.methodName}/${it.outcome}" }
        )
        assertEquals(125L, results.first().durationMillis)
        assertEquals(null, results.first { it.methodName == "pending" }.durationMillis)
    }

    @Test
    fun `ignores stale oversized malformed and doctype reports`() {
        val reports = reportDirectory()
        val stale = reports.resolve("TEST-stale.xml")
        Files.writeString(
            stale,
            """<testsuite><testcase classname="example.StaleTest" name="old" time="0.1"/></testsuite>"""
        )
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(60)))

        Files.writeString(reports.resolve("TEST-oversized.xml"), "x".repeat((SurefireReports.MAX_REPORT_BYTES + 1).toInt()))
        Files.writeString(reports.resolve("TEST-malformed.xml"), "<testsuite><testcase")
        Files.writeString(
            reports.resolve("TEST-doctype.xml"),
            """
            <!DOCTYPE testsuite [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <testsuite><testcase classname="example.DoctypeTest" name="leak" time="0.1">&xxe;</testcase></testsuite>
            """.trimIndent()
        )

        assertEquals(emptyList<Any>(), SurefireReports.read(temporary, Instant.now()))
    }

    @Test
    fun `accepts a report written within the stale grace window`() {
        val reports = reportDirectory()
        Files.writeString(
            reports.resolve("TEST-example.FreshTest.xml"),
            """<testsuite><testcase classname="example.FreshTest" name="fresh" time="0.1"/></testsuite>"""
        )

        val results = SurefireReports.read(temporary, Instant.now())

        assertEquals(1, results.size)
        assertEquals("fresh", results.single().methodName)
        assertEquals(CheckOutcome.PASSED, results.single().outcome)
    }

    @Test
    fun `returns no evidence when the report directory is absent`() {
        assertTrue(SurefireReports.read(temporary).isEmpty())
    }

    private fun reportDirectory(): Path =
        Files.createDirectories(temporary.resolve("target").resolve("surefire-reports"))
}

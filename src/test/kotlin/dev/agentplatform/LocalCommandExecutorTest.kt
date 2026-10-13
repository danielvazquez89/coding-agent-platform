package dev.agentplatform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

class LocalCommandExecutorTest {
    private val executor = LocalCommandExecutor()

    @Test
    fun `runs commands with standard input and requested environment`() {
        val command = javaCommand("stdin")
        val result = executor.run(
            command,
            standardInput = "hello from stdin\n",
            environment = mapOf("AGENT_TEST_VALUE" to "present")
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello from stdin|present", result.output.trim())
        assertFalse(result.timedOut)
    }

    @Test
    fun `bounds command output and reports a timeout`() {
        val java = javaExecutable()
        val truncated = executor.run(listOf(java, "-version"), maxOutputChars = 8)
        assertEquals(0, truncated.exitCode)
        assertTrue(truncated.output.contains("[output truncated]"))

        val timedOut = executor.run(
            javaCommand("sleep", "3000"),
            timeout = Duration.ofMillis(100)
        )
        assertTrue(timedOut.timedOut)
        assertEquals(-1, timedOut.exitCode)
    }

    @Test
    fun `reports invalid commands and process startup failures`() {
        assertThrows(IllegalArgumentException::class.java) { executor.run(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { executor.run(listOf(" ")) }
        assertThrows(IllegalArgumentException::class.java) { executor.run(javaCommand("stdin"), timeout = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { executor.run(javaCommand("stdin"), maxOutputChars = 0) }
        assertThrows(IllegalStateException::class.java) { executor.run(listOf("agent-command-that-does-not-exist")) }
    }

    private fun javaCommand(vararg arguments: String): List<String> =
        listOf(javaExecutable(), "-cp", System.getProperty("java.class.path"), CommandFixture::class.java.name, *arguments)

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executable).toString()
    }
}

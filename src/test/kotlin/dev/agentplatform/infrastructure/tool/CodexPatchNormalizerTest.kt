package dev.agentplatform.infrastructure.tool

import dev.agentplatform.infrastructure.process.LocalCommandExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class CodexPatchNormalizerTest {
    private lateinit var workspace: Path
    private val normalizer = CodexPatchNormalizer()
    private val commands = LocalCommandExecutor()

    @BeforeEach
    fun createWorkspace() {
        val parent = Path.of("target", "test-workspaces").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        workspace = Files.createTempDirectory(parent, "codex-patch-")
        Files.writeString(workspace.resolve("README.md"), "initial\n")
        run("init", "--initial-branch=main")
        run("add", "README.md")
        run("commit", "-m", "initial")
    }

    @AfterEach
    fun removeWorkspace() {
        if (Files.exists(workspace)) Files.walk(workspace).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `leaves unified diffs untouched`() {
        val diff = "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-initial\n+changed\n"
        assertNull(normalizer.normalize(diff, workspace))
        assertFalse(normalizer.isEnvelope(diff))
    }

    @Test
    fun `recognises an envelope behind blank lines and CRLF`() {
        assertTrue(normalizer.isEnvelope("\r\n  *** Begin Patch\r\n*** Update File: README.md\r\n*** End Patch\r\n"))
        assertFalse(normalizer.isEnvelope("   "))
    }

    @Test
    fun `converts an update envelope into a diff that git applies`() {
        Files.writeString(workspace.resolve("Hello.kt"), "package sample\n\nval greeting = \"old\"\n")

        val diff = normalizer.normalize(
            """
            |*** Begin Patch
            |*** Update File: Hello.kt
            |@@
            | package sample
            |
            |-val greeting = "old"
            |+val greeting = "new"
            |*** End Patch
            |""".trimMargin(),
            workspace
        )

        assertNotNull(diff)
        assertTrue(diff!!.contains("diff --git a/Hello.kt b/Hello.kt"))
        assertTrue(diff.contains("@@ -1,3 +1,3 @@"))
        apply(diff)
        assertEquals("package sample\n\nval greeting = \"new\"\n", Files.readString(workspace.resolve("Hello.kt")))
    }

    @Test
    fun `adds and deletes files in one envelope`() {
        Files.writeString(workspace.resolve("Old.kt"), "one\ntwo\n")

        val diff = normalizer.normalize(
            """
            |*** Begin Patch
            |*** Add File: src/New.kt
            |+package sample
            |+
            |+val added = true
            |*** Delete File: Old.kt
            |*** End Patch
            |""".trimMargin(),
            workspace
        )

        apply(diff!!)
        assertEquals("package sample\n\nval added = true\n", Files.readString(workspace.resolve("src/New.kt")))
        assertFalse(Files.exists(workspace.resolve("Old.kt")))
    }

    @Test
    fun `places several hunks in one file`() {
        Files.writeString(workspace.resolve("Numbers.kt"), "one\ntwo\nthree\nfour\nfive\n")

        val diff = normalizer.normalize(
            """
            |*** Begin Patch
            |*** Update File: Numbers.kt
            |@@
            | one
            |-two
            |+TWO
            |@@
            | four
            |-five
            |+FIVE
            |*** End Patch
            |""".trimMargin(),
            workspace
        )

        apply(diff!!)
        assertEquals("one\nTWO\nthree\nfour\nFIVE\n", Files.readString(workspace.resolve("Numbers.kt")))
    }

    @Test
    fun `reports a hunk that does not match instead of guessing`() {
        Files.writeString(workspace.resolve("Hello.kt"), "actual\n")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            normalizer.normalize(
                "*** Begin Patch\n*** Update File: Hello.kt\n@@\n-expected\n+replacement\n*** End Patch",
                workspace
            )
        }

        assertTrue(failure.message.orEmpty().contains("does not match the current content"))
        assertTrue(failure.message.orEmpty().contains("No line whose text is 'expected' exists"))
        assertEquals("actual\n", Files.readString(workspace.resolve("Hello.kt")))
    }

    @Test
    fun `returns the actual indented lines for a whitespace-only mismatch`() {
        Files.writeString(workspace.resolve("Indented.kt"), "fun main() {\n    val x = 1\n}\n")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            normalizer.normalize(
                "*** Begin Patch\n*** Update File: Indented.kt\n@@\n-val x = 1\n+val x = 2\n*** End Patch",
                workspace
            )
        }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("does not match the current content"), message)
        assertTrue(message.contains("The file has this at line 2:"), message)
        assertTrue(message.contains("|    val x = 1"), message)
        assertTrue(message.contains("including leading whitespace"), message)
        assertEquals("fun main() {\n    val x = 1\n}\n", Files.readString(workspace.resolve("Indented.kt")))
    }

    @Test
    fun `rejects moves, missing files, missing end marker and unsafe paths`() {
        val move = "*** Begin Patch\n*** Update File: README.md\n*** Move to: Other.md\n@@\n-initial\n+moved\n*** End Patch"
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { normalizer.normalize(move, workspace) }
                .message.orEmpty().contains("not supported")
        )

        val missing = "*** Begin Patch\n*** Update File: Absent.kt\n@@\n-a\n+b\n*** End Patch"
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { normalizer.normalize(missing, workspace) }
                .message.orEmpty().contains("does not exist")
        )

        val unterminated = "*** Begin Patch\n*** Delete File: README.md"
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { normalizer.normalize(unterminated, workspace) }
                .message.orEmpty().contains("missing")
        )

        val escaping = "*** Begin Patch\n*** Update File: ../outside.txt\n@@\n-a\n+b\n*** End Patch"
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { normalizer.normalize(escaping, workspace) }
                .message.orEmpty().contains("inside the repository")
        )
    }

    @Test
    fun `rejects an update without hunks or with an insertion that has no context`() {
        val noHunk = "*** Begin Patch\n*** Update File: README.md\n*** End Patch"
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { normalizer.normalize(noHunk, workspace) }
                .message.orEmpty().contains("at least one")
        )

        val noContext = "*** Begin Patch\n*** Update File: README.md\n@@\n+appended\n*** End Patch"
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { normalizer.normalize(noContext, workspace) }
                .message.orEmpty().contains("ambiguous")
        )
    }

    @Test
    fun `accepts CRLF input and the end of file marker`() {
        Files.writeString(workspace.resolve("Hello.kt"), "one\ntwo\n")

        val diff = normalizer.normalize(
            ("*** Begin Patch\r\n*** Update File: Hello.kt\r\n@@\r\n one\r\n+two-and-a-half\r\n*** End of File\r\n*** End Patch\r\n"),
            workspace
        )

        apply(diff!!)
        assertEquals("one\ntwo-and-a-half\ntwo\n", Files.readString(workspace.resolve("Hello.kt")))
    }

    private fun apply(diff: String) {
        val check = commands.run(
            listOf("git", "-c", "core.autocrlf=false", "apply", "--check", "--recount", "--whitespace=nowarn", "-"),
            workspace,
            diff,
            timeout = Duration.ofSeconds(20)
        )
        check(check.exitCode == 0) { check.output }
        run("-c", "core.autocrlf=false", "apply", "--recount", "--whitespace=nowarn", "-", standardInput = diff)
    }

    private fun run(vararg args: String, standardInput: String? = null) {
        val result = commands.run(listOf("git", *args), workspace, standardInput, Duration.ofSeconds(20))
        check(result.exitCode == 0) { result.output }
    }
}

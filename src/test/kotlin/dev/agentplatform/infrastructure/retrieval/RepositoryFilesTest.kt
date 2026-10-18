package dev.agentplatform.infrastructure.retrieval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class RepositoryFilesTest {
    private lateinit var workspace: Path

    @BeforeEach
    fun createWorkspace() {
        val parent = Path.of("target", "test-workspaces").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        workspace = Files.createTempDirectory(parent, "repository-files-")
    }

    @AfterEach
    fun removeWorkspace() {
        if (Files.exists(workspace)) Files.walk(workspace).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `walks text files within bounds and skips what it must not read`() {
        write("src/main/Text.kt", "class Text { fun value() = 1 }\n")
        write("src/main/Empty.kt", "")
        write("src/main/Binary.kt", "a\u0000b")
        write("src/main/Huge.kt", "x".repeat(200))
        write("target/Generated.kt", "class Generated\n")
        link("src/main/Linked.kt", workspace.resolve("src/main/Text.kt"))
        link("LinkedDirectory", workspace.resolve("src/main"))

        val files = RepositoryFiles.walk(workspace, maxFiles = 100, maxFileBytes = 100, maxTotalBytes = 1_000)
            .map { RepositoryFiles.relative(workspace, it) }

        assertTrue(files.contains("src/main/Text.kt"))
        assertTrue(files.contains("src/main/Empty.kt"))
        assertFalse(files.contains("src/main/Binary.kt"))
        assertFalse(files.contains("src/main/Huge.kt"))
        assertFalse(files.any { it.startsWith("target/") })
        assertFalse(files.any { it.contains("Linked", ignoreCase = true) })
    }

    @Test
    fun `stops at the file count and byte caps`() {
        repeat(5) { index -> write("src/File${index}.kt", "class File${index} {}\n") }

        assertEquals(
            2,
            RepositoryFiles.walk(workspace, maxFiles = 2, maxFileBytes = 1_000, maxTotalBytes = 1_000_000).size
        )
        assertTrue(
            RepositoryFiles.walk(workspace, maxFiles = 10, maxFileBytes = 1_000, maxTotalBytes = 0).isEmpty()
        )
    }

    private fun write(relative: String, content: String) {
        val file = workspace.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    /** Symlink creation needs a privilege the host may not grant; the walk must hold either way. */
    private fun link(relative: String, target: Path) {
        try {
            Files.createSymbolicLink(workspace.resolve(relative), target)
        } catch (_: Exception) {
            // Not supported here; the corresponding branch stays untested on this host.
        }
    }
}

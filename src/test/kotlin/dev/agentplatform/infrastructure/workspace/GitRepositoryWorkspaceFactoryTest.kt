package dev.agentplatform.infrastructure.workspace

import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import dev.agentplatform.infrastructure.process.LocalCommandExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class GitRepositoryWorkspaceFactoryTest {
    private lateinit var temporary: Path

    @BeforeEach
    fun createTestDirectory() {
        val parent = Path.of("target", "test-workspaces").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        temporary = Files.createTempDirectory(parent, "git-workspace-")
    }

    @AfterEach
    fun removeTestDirectory() {
        if (Files.exists(temporary)) Files.walk(temporary).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `clones local repository, reports tracked and untracked changes, and removes workspace`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed"))
        val source = Files.createDirectory(allowed.resolve("sample"))
        initializeRepository(source)
        Files.writeString(source.resolve(".env"), "PASSWORD=must-not-leak")
        Files.writeString(source.resolve("id_rsa"), "private-key")
        val factory = GitRepositoryWorkspaceFactory(
            LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces").toString()
        )

        val workspace = factory.open(source.toString())
        val clonePath = workspace.root
        assertEquals("initial text\n", Files.readString(clonePath.resolve("README.md")))
        assertFalse(Files.exists(clonePath.resolve(".env")))
        assertFalse(Files.exists(clonePath.resolve("id_rsa")))
        assertTrue(Files.exists(clonePath.resolve(".env.example")))
        Files.writeString(clonePath.resolve("README.md"), "updated text\n")
        Files.writeString(clonePath.resolve("new.txt"), "new content\n")

        assertEquals(listOf("README.md", "new.txt"), workspace.changedFiles())
        assertTrue(workspace.patchDiff(5000).contains("updated text"))
        assertTrue(workspace.patchDiff(5000).contains("new content"))
        workspace.close()
        assertFalse(Files.exists(clonePath))
    }

    @Test
    fun `normalizes CRLF source files to LF so model patches apply cleanly`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed"))
        val source = Files.createDirectory(allowed.resolve("crlf-repo"))
        initializeRepository(source)
        Files.writeString(source.resolve("Greeting.java"), "line1\r\nreturn \"Hello\";\r\nline3\r\n")
        val commands = LocalCommandExecutor()
        check(commands.run(listOf("git", "add", "Greeting.java"), source, timeout = Duration.ofSeconds(20)).exitCode == 0)
        check(commands.run(listOf("git", "commit", "-m", "crlf"), source, timeout = Duration.ofSeconds(20)).exitCode == 0)

        val workspace = GitRepositoryWorkspaceFactory(
            LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces").toString()
        ).open(source.toString())
        try {
            val normalized = Files.readString(workspace.root.resolve("Greeting.java"))
            assertFalse(normalized.contains('\r'))
            assertEquals("line1\nreturn \"Hello\";\nline3\n", normalized)
        } finally {
            workspace.close()
        }
    }

    @Test
    fun `rejects unsafe or out of scope repository sources`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed"))
        val validRepo = Files.createDirectory(allowed.resolve("repo"))
        Files.createDirectory(validRepo.resolve(".git"))
        val factory = GitRepositoryWorkspaceFactory(LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces").toString())

        assertThrows(IllegalArgumentException::class.java) { factory.open(" ") }
        assertThrows(IllegalArgumentException::class.java) { factory.open(temporary.toString()) }
        assertThrows(IllegalArgumentException::class.java) { factory.open(temporary.resolve("outside").toString()) }
        assertThrows(IllegalArgumentException::class.java) { factory.open("http://github.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://example.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://user:secret@github.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://github.com/owner/repo?token=secret") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://github.com/owner/repo/tree/main") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://github.com/owner/repo%ZZ") }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryWorkspaceFactory(LocalCommandExecutor(), "", temporary.resolve("workspaces").toString()).open(validRepo.toString())
        }
    }

    @Test
    fun `accepts safe GitHub URL and cleans up if clone fails`() {
        val workspaceRoot = temporary.resolve("workspaces")
        val fake = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ): CommandResult = when {
                command.contains("clone") -> CommandResult(0, "cloned")
                command.contains("rev-parse") -> CommandResult(0, "true\n")
                else -> CommandResult(0, "")
            }
        }
        val factory = GitRepositoryWorkspaceFactory(fake, "", workspaceRoot.toString())
        val workspace = factory.open("https://github.com/example/project.git")
        assertTrue(workspace.root.startsWith(workspaceRoot))
        workspace.close()

        val failing = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ) = CommandResult(128, "clone failed")
        }
        assertThrows(IllegalStateException::class.java) {
            GitRepositoryWorkspaceFactory(failing, "", workspaceRoot.toString()).open("https://github.com/example/project")
        }
        Files.list(workspaceRoot).use { assertEquals(0L, it.count()) }
    }

    private fun initializeRepository(path: Path) {
        val commands = LocalCommandExecutor()
        fun run(vararg args: String) {
            val result = commands.run(listOf("git", *args), path, timeout = Duration.ofSeconds(20))
            check(result.exitCode == 0) { result.output }
        }
        run("init", "--initial-branch=main")
        run("config", "user.name", "Coding Agent Tests")
        run("config", "user.email", "agent-tests@example.invalid")
        Files.writeString(path.resolve("README.md"), "initial text\n")
        Files.writeString(path.resolve(".gitignore"), ".env\n")
        Files.writeString(path.resolve(".env.example"), "EXAMPLE=true\n")
        run("add", "README.md", ".gitignore", ".env.example")
        run("commit", "-m", "initial")
    }
}

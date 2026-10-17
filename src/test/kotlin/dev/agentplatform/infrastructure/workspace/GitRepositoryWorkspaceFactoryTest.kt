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
        val patch = workspace.patchDiff(5000)
        assertFalse(patch.truncated)
        assertTrue(patch.diff.contains("updated text"))
        assertTrue(patch.diff.contains("new content"))
        workspace.close()
        assertFalse(Files.exists(clonePath))
    }

    @Test
    fun `excludes offline validation build output from changed files and diff`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed-build-output"))
        val source = Files.createDirectory(allowed.resolve("sample"))
        initializeRepository(source)
        val factory = GitRepositoryWorkspaceFactory(
            LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces-build-output").toString()
        )

        val workspace = factory.open(source.toString())
        try {
            val clonePath = workspace.root
            Files.writeString(clonePath.resolve("README.md"), "updated text\n")
            Files.createDirectories(clonePath.resolve("target/classes"))
            Files.createDirectories(clonePath.resolve("module/target"))
            Files.writeString(clonePath.resolve("target/classes/Build.class"), "bytecode")
            Files.writeString(clonePath.resolve("module/target/report.txt"), "maven build output")

            assertEquals(listOf("README.md"), workspace.changedFiles())
            val patch = workspace.patchDiff(20_000)
            assertFalse(patch.truncated)
            assertTrue(patch.diff.contains("updated text"))
            assertFalse(patch.diff.contains("Build.class"))
            assertFalse(patch.diff.contains("maven build output"))
        } finally {
            workspace.close()
        }
    }

    @Test
    fun `reports truncation instead of returning an unappliable patch`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed-truncated"))
        val source = Files.createDirectory(allowed.resolve("sample"))
        initializeRepository(source)
        val factory = GitRepositoryWorkspaceFactory(
            LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces-truncated").toString()
        )

        val workspace = factory.open(source.toString())
        try {
            val clonePath = workspace.root
            Files.writeString(clonePath.resolve("README.md"), (1..500).joinToString("\n") { "line $it" } + "\n")

            val full = workspace.patchDiff(1_000_000)
            assertFalse(full.truncated)
            assertTrue(full.diff.contains("line 500"))
            assertTrue(full.diff.length > 200)

            val capped = workspace.patchDiff(200)
            assertTrue(capped.truncated)
            assertEquals(200, capped.diff.length)
        } finally {
            workspace.close()
        }
    }

    @Test
    fun `excludes sensitive local files while preserving example environment files`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed-sensitive"))
        val source = Files.createDirectory(allowed.resolve("sample"))
        initializeRepository(source)
        listOf(".env.production", "certificate.pem", "signing.key", "id_ed25519", "credentials.json").forEach {
            Files.writeString(source.resolve(it), "sensitive")
        }
        Files.createDirectories(source.resolve(".aws"))
        Files.writeString(source.resolve(".aws/credentials"), "sensitive")
        Files.createDirectories(source.resolve(".ssh"))
        Files.writeString(source.resolve(".ssh/config"), "sensitive")
        listOf(".env.example", ".env.sample", ".env.template").forEach {
            Files.writeString(source.resolve(it), "safe")
        }
        val workspace = GitRepositoryWorkspaceFactory(
            LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces-sensitive").toString()
        ).open(source.toString())

        try {
            listOf(".env.production", "certificate.pem", "signing.key", "id_ed25519", "credentials.json",
                ".aws/credentials", ".ssh/config").forEach { assertFalse(Files.exists(workspace.root.resolve(it)), it) }
            listOf(".env.example", ".env.sample", ".env.template").forEach {
                assertTrue(Files.exists(workspace.root.resolve(it)), it)
            }
        } finally {
            workspace.close()
        }
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
    fun `leaves binary and oversized files unchanged during line ending normalization`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed-normalization-edges"))
        val source = Files.createDirectory(allowed.resolve("repo"))
        initializeRepository(source)
        val binary = byteArrayOf('a'.code.toByte(), '\r'.code.toByte(), 0, '\n'.code.toByte())
        Files.write(source.resolve("binary.dat"), binary)
        val large = "x\r\n".repeat(700_000)
        Files.writeString(source.resolve("large.txt"), large)
        val commands = LocalCommandExecutor()
        check(commands.run(listOf("git", "add", "binary.dat", "large.txt"), source).exitCode == 0)
        check(commands.run(listOf("git", "commit", "-m", "binary and large"), source).exitCode == 0)

        val workspace = GitRepositoryWorkspaceFactory(
            LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces-normalization-edges").toString()
        ).open(source.toString())
        try {
            assertArrayEquals(binary, Files.readAllBytes(workspace.root.resolve("binary.dat")))
            assertEquals(large, Files.readString(workspace.root.resolve("large.txt")))
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
        val nonRepository = Files.createDirectory(allowed.resolve("not-a-repository"))

        assertThrows(IllegalArgumentException::class.java) { factory.open(" ") }
        assertThrows(IllegalArgumentException::class.java) { factory.open(temporary.toString()) }
        assertThrows(IllegalArgumentException::class.java) { factory.open(temporary.resolve("outside").toString()) }
        assertThrows(IllegalArgumentException::class.java) { factory.open(nonRepository.toString()) }
        assertThrows(IllegalArgumentException::class.java) { factory.open("http://github.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://example.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://user:secret@github.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://github.com/owner/repo?token=secret") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://github.com/owner/repo/tree/main") }
        assertThrows(IllegalArgumentException::class.java) { factory.open("https://github.com/owner/repo%ZZ") }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryWorkspaceFactory(LocalCommandExecutor(), "", temporary.resolve("workspaces").toString()).open(validRepo.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitRepositoryWorkspaceFactory(LocalCommandExecutor(), "bad\u0000root", temporary.resolve("workspaces").toString())
                .open(validRepo.toString())
        }
    }

    @Test
    fun `rejects local repository symlinks and symlink traversal when supported`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed-symlink"))
        val external = Files.createDirectory(temporary.resolve("external-symlink"))
        val externalRepository = Files.createDirectory(external.resolve("repo"))
        initializeRepository(externalRepository)
        val factory = GitRepositoryWorkspaceFactory(
            LocalCommandExecutor(), allowed.toString(), temporary.resolve("workspaces-symlink").toString()
        )
        try {
            val directLink = allowed.resolve("direct-link")
            Files.createSymbolicLink(directLink, externalRepository)
            assertThrows(IllegalArgumentException::class.java) { factory.open(directLink.toString()) }

            val parentLink = allowed.resolve("external")
            Files.createSymbolicLink(parentLink, external)
            assertThrows(IllegalArgumentException::class.java) {
                factory.open(parentLink.resolve("repo").toString())
            }
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.io.IOException) {
            return
        } catch (_: SecurityException) {
            return
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

    @Test
    fun `cleans up when a remote clone is not a working tree`() {
        val workspaceRoot = temporary.resolve("workspaces-invalid-clone")
        val fake = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ) = when {
                command.contains("clone") -> CommandResult(0, "cloned")
                command.contains("rev-parse") -> CommandResult(1, "not a repository")
                else -> CommandResult(0, "")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            GitRepositoryWorkspaceFactory(fake, "", workspaceRoot.toString())
                .open("https://github.com/example/project")
        }
        Files.list(workspaceRoot).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `cleans up when a local repository file listing fails`() {
        val allowed = Files.createDirectory(temporary.resolve("allowed-list-failure"))
        val source = Files.createDirectory(allowed.resolve("repo"))
        initializeRepository(source)
        val failing = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ) = CommandResult(1, "git ls-files failed")
        }
        val workspaceRoot = temporary.resolve("workspaces-list-failure")

        assertThrows(IllegalStateException::class.java) {
            GitRepositoryWorkspaceFactory(failing, allowed.toString(), workspaceRoot.toString()).open(source.toString())
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

package dev.agentplatform

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class WorkspaceToolExecutorTest {
    private lateinit var temporary: Path
    private val mapper = ObjectMapper()

    @BeforeEach
    fun createTestDirectory() {
        val parent = Path.of("target", "test-workspaces").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        temporary = Files.createTempDirectory(parent, "workspace-tool-")
    }

    @AfterEach
    fun removeTestDirectory() {
        if (Files.exists(temporary)) Files.walk(temporary).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `offers only the five bounded workspace tools`() {
        val tools = executor().definitions()
        assertEquals(listOf("list_files", "search_code", "read_file", "apply_patch", "run_validation"), tools.map { it.name })
        assertTrue(tools.all { it.parameters.path("additionalProperties").asBoolean() == false })
    }

    @Test
    fun `lists searches and reads repository files while skipping generated directories`() {
        val root = repository()
        Files.createDirectories(root.resolve("src/main"))
        Files.writeString(root.resolve("src/main/Sample.kt"), "package sample\nval greeting = \"hello agent\"\n")
        Files.createDirectories(root.resolve("target/classes"))
        Files.writeString(root.resolve("target/classes/Generated.kt"), "hello")
        Files.createDirectory(root.resolve("empty"))
        Files.write(root.resolve("broken.txt"), byteArrayOf(0xc3.toByte(), 0x28))
        val tools = executor()

        val files = tools.execute("list_files", mapper.createObjectNode(), root).content
        assertTrue(files.contains("src/main/Sample.kt"))
        assertTrue(!files.contains("target/classes"))
        val search = tools.execute("search_code", mapper.readTree("""{"query":"HELLO","path":"src"}"""), root).content
        assertTrue(search.contains("Sample.kt:2: val greeting"))
        assertEquals("No matches found.", tools.execute("search_code", mapper.readTree("""{"query":"absent"}"""), root).content)
        assertTrue(tools.execute("search_code", mapper.readTree("""{"query":"sample"}"""), root).content.isNotBlank())
        assertTrue(tools.execute("read_file", mapper.readTree("""{"path":"src/main/Sample.kt"}"""), root).content.contains("hello agent"))
        assertEquals("No files found.", tools.execute("list_files", mapper.readTree("""{"path":"empty"}"""), root).content)
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("list_files", mapper.readTree("""{"path":"missing"}"""), root)
        }
    }

    @Test
    fun `rejects missing or unsafe paths oversized and binary files and unsupported tools`() {
        val root = repository()
        Files.writeString(root.resolve("large.txt"), "x".repeat(50_001))
        Files.write(root.resolve("binary.dat"), byteArrayOf(0x00, 0x01))
        val tools = executor()
        assertThrows(IllegalArgumentException::class.java) { tools.execute("unknown", mapper.createObjectNode(), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("read_file", mapper.readTree("{}"), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("read_file", mapper.readTree("""{"path":"../outside"}"""), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("read_file", mapper.readTree("""{"path":"missing.txt"}"""), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("read_file", mapper.readTree("""{"path":"large.txt"}"""), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("read_file", mapper.readTree("""{"path":"binary.dat"}"""), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("search_code", mapper.readTree("""{"query":" "}"""), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("search_code", mapper.readTree("""{"query":"x","path":"../"}"""), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("search_code", mapper.readTree("{}"), root) }
        assertThrows(IllegalArgumentException::class.java) { tools.execute("read_file", mapper.readTree("""{"path":" "}"""), root) }
    }

    @Test
    fun `applies only bounded in-repository unified patches`() {
        val root = repository()
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Hello.kt"), "old\n")
        val tools = executor()
        val patch = """diff --git a/src/Hello.kt b/src/Hello.kt
--- a/src/Hello.kt
+++ b/src/Hello.kt
@@ -1 +1 @@
-old
+new
"""

        val result = tools.execute("apply_patch", mapper.createObjectNode().put("patch", patch), root)
        assertTrue(result.content.contains("Patch applied"))
        assertEquals("new\n", Files.readString(root.resolve("src/Hello.kt")))

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode().put("patch", ""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode().put("patch", "x".repeat(64_001)), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode().put("patch", "--- a/../../outside\n+++ b/../../outside\n@@ -1 +1 @@\n-a\n+b\n"), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode().put("patch", "--- a/src/Hello.kt\n+++ b/src/Hello.kt\n@@ -1 +1 @@\n-wrong\n+new\n"), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode().put("patch", "--- a/src/Hello.kt\n+++ b/src/Hello.kt\n"), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode(), root)
        }
    }

    @Test
    fun `returns validation outcomes and rejects model supplied extra commands`() {
        val check = ValidationCheck("Maven tests", "mvn test", CheckOutcome.PASSED, 0, "passed", 100)
        val tools = WorkspaceToolExecutor(object : ValidationRunner {
            override fun validate(workspace: Path) = check
        }, LocalCommandExecutor(), mapper)
        val result = tools.execute("run_validation", mapper.createObjectNode(), temporary)
        assertEquals(check, result.validation)
        assertTrue(result.content.contains("PASSED"))
    }

    @Test
    fun `rejects symbolic links when the host permits creating them`() {
        val root = repository()
        val external = Files.writeString(temporary.resolve("outside.txt"), "outside")
        try {
            Files.createSymbolicLink(root.resolve("linked.txt"), external)
        } catch (_: Exception) {
            return
        }
        assertThrows(IllegalArgumentException::class.java) {
            executor().execute("read_file", mapper.readTree("""{"path":"linked.txt"}"""), root)
        }
        assertTrue(executor().execute("list_files", mapper.createObjectNode(), root).content.isNotBlank())
    }

    private fun executor() = WorkspaceToolExecutor(object : ValidationRunner {
        override fun validate(workspace: Path) = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED)
    }, LocalCommandExecutor(), mapper)

    private fun repository(): Path {
        val root = Files.createTempDirectory(temporary, "repo-")
        val commands = LocalCommandExecutor()
        fun run(vararg args: String) {
            val result = commands.run(listOf("git", *args), root, timeout = Duration.ofSeconds(20))
            check(result.exitCode == 0) { result.output }
        }
        run("init", "--initial-branch=main")
        run("config", "user.name", "Coding Agent Tests")
        run("config", "user.email", "agent-tests@example.invalid")
        Files.writeString(root.resolve("README.md"), "initial\n")
        run("add", "README.md")
        run("commit", "-m", "initial")
        return root
    }
}

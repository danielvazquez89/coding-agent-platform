package dev.agentplatform.infrastructure.tool

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import dev.agentplatform.domain.model.ValidationCheck
import dev.agentplatform.domain.port.ValidationRunner
import dev.agentplatform.infrastructure.metrics.MicrometerExecutionMetrics
import dev.agentplatform.infrastructure.process.LocalCommandExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class WorkspaceToolExecutorTest {
    private lateinit var temporary: Path
    private val mapper = ObjectMapper()
    private val registry = SimpleMeterRegistry()

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
    fun `offers only the bounded workspace tools`() {
        val tools = executor().definitions()
        assertEquals(
            listOf(
                "list_files", "search_code", "find_symbol", "read_file", "create_file", "related_tests",
                "apply_patch", "run_validation"
            ),
            tools.map { it.name }
        )
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
    fun `finds declaration sites for a symbol by path and line`() {
        val root = repository()
        Files.createDirectories(root.resolve("src/main/java/dev/example"))
        Files.createDirectories(root.resolve("src/main/kotlin/sample"))
        Files.createDirectories(root.resolve("src/test/java/dev/example"))
        Files.writeString(
            root.resolve("src/main/java/dev/example/Writer.java"),
            """
            |package dev.example;
            |
            |public final class Writer {
            |    private final StringBuilder buffer = new StringBuilder();
            |
            |    public static String writeValue(Object value) {
            |        return render(value);
            |    }
            |}
            |""".trimMargin() + "\n"
        )
        Files.writeString(
            root.resolve("src/main/kotlin/sample/Sample.kt"),
            """
            |package sample
            |
            |fun writeValue(x: Int): Int {
            |    return x
            |}
            |""".trimMargin() + "\n"
        )
        Files.writeString(
            root.resolve("src/test/java/dev/example/WriterTest.java"),
            """
            |package dev.example;
            |
            |public class WriterTest {
            |    void checks() {
            |        Writer.writeValue(value);
            |    }
            |}
            |""".trimMargin() + "\n"
        )
        val tools = executor()

        val method = tools.execute("find_symbol", mapper.readTree("""{"name":"writeValue"}"""), root).content
        assertTrue(method.contains("src/main/java/dev/example/Writer.java:6: [method]"), method)
        assertTrue(method.contains("src/main/kotlin/sample/Sample.kt:3: [function]"), method)
        assertTrue(method.contains("src/test/java/dev/example/WriterTest.java:5: [reference]"), method)
        assertTrue(
            method.indexOf("Writer.java:6") < method.indexOf("WriterTest.java:5"),
            "a declaration must be listed before a reference: $method"
        )

        val type = tools.execute("find_symbol", mapper.readTree("""{"name":"Writer"}"""), root).content
        assertTrue(type.contains("src/main/java/dev/example/Writer.java:3: [type]"), type)
        val field = tools.execute("find_symbol", mapper.readTree("""{"name":"buffer"}"""), root).content
        assertTrue(field.contains("src/main/java/dev/example/Writer.java:4: [field]"), field)

        val bounded = tools.execute("find_symbol", mapper.readTree("""{"name":"writeValue","maxResults":1}"""), root).content
        assertEquals(1, bounded.lines().count { it.trimStart().startsWith("src/") })

        assertEquals(
            "No declaration or occurrence of 'absentSymbol' found.",
            tools.execute("find_symbol", mapper.readTree("""{"name":"absentSymbol"}"""), root).content
        )
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("find_symbol", mapper.readTree("""{"name":"not a symbol"}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("find_symbol", mapper.readTree("""{"name":"writeValue","path":"../"}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("find_symbol", mapper.createObjectNode(), root)
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

        assertEquals(6.0, registry.counter("agent.tool.calls", "tool", "read_file", "outcome", "rejected").count())
        assertEquals(1.0, registry.counter("agent.tool.calls", "tool", "unknown", "outcome", "rejected").count())
    }

    @Test
    fun `creates a new file but never overwrites an existing one`() {
        val root = repository()
        val tools = executor()

        val created = tools.execute(
            "create_file",
            mapper.createObjectNode().put("path", "src/main/New.kt").put("content", "package sample\n"),
            root
        )
        assertTrue(created.content.contains("Created src/main/New.kt"))
        assertEquals("package sample\n", Files.readString(root.resolve("src/main/New.kt")))

        val empty = tools.execute(
            "create_file",
            mapper.createObjectNode().put("path", "empty.txt").put("content", ""),
            root
        )
        assertTrue(empty.content.contains("Created empty.txt"))
        assertEquals(0L, Files.size(root.resolve("empty.txt")))

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("create_file", mapper.createObjectNode().put("path", "README.md").put("content", "clobbered"), root)
        }
        assertEquals("initial\n", Files.readString(root.resolve("README.md")))
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("create_file", mapper.createObjectNode().put("path", "../outside.txt").put("content", "x"), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("create_file", mapper.createObjectNode().put("path", "binary.dat").put("content", "a\u0000b"), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute(
                "create_file",
                mapper.createObjectNode().put("path", "huge.txt").put("content", "x".repeat(64_001)),
                root
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("create_file", mapper.createObjectNode().put("path", "missing-content.txt"), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("create_file", mapper.createObjectNode().put("content", "orphan"), root)
        }
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
    fun `applies model-style patches with wrong hunk counts and markdown fences`() {
        val root = repository()
        Files.createDirectories(root.resolve("src/main/java/dev/example"))
        Files.writeString(
            root.resolve("src/main/java/dev/example/Greeting.java"),
            """
            |package dev.example;
            |
            |public final class Greeting {
            |    private Greeting() {}
            |
            |    public static String message() {
            |        return "Hello";
            |    }
            |}
            |""".trimMargin() + "\n"
        )
        val commands = LocalCommandExecutor()
        check(
            commands.run(listOf("git", "add", "--all"), root, timeout = Duration.ofSeconds(20)).exitCode == 0
        )
        check(
            commands.run(
                listOf("git", "commit", "-m", "greeting"),
                root,
                timeout = Duration.ofSeconds(20)
            ).exitCode == 0
        )

        val miscounted = """
            |```diff
            |diff --git a/src/main/java/dev/example/Greeting.java b/src/main/java/dev/example/Greeting.java
            |--- a/src/main/java/dev/example/Greeting.java
            |+++ b/src/main/java/dev/example/Greeting.java
            |@@ -10,7 +10,7 @@ public final class Greeting {
            |     private Greeting() {}
            | 
            |     public static String message() {
            |-        return "Hello";
            |+        return "Hello from Qwen";
            |     }
            | }
            |```
            |""".trimMargin()

        val result = executor().execute("apply_patch", mapper.createObjectNode().put("patch", miscounted), root)
        assertTrue(result.content.contains("Patch applied"))
        assertTrue(Files.readString(root.resolve("src/main/java/dev/example/Greeting.java")).contains("Hello from Qwen"))
    }

    @Test
    fun `applies a Codex envelope patch for the gpt-oss family`() {
        val root = repository()
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Hello.kt"), "old\n")
        val envelope = """
            |```text
            |*** Begin Patch
            |*** Update File: src/Hello.kt
            |@@
            |-old
            |+new
            |*** Add File: src/New.kt
            |+package sample
            |*** End Patch
            |```
            |""".trimMargin()

        val result = executor().execute("apply_patch", mapper.createObjectNode().put("patch", envelope), root)

        assertTrue(result.content.contains("Patch applied to 2 file(s)"))
        assertEquals("new\n", Files.readString(root.resolve("src/Hello.kt")))
        assertEquals("package sample\n", Files.readString(root.resolve("src/New.kt")))
    }

    @Test
    fun `returns validation outcomes and rejects model supplied extra commands`() {
        val check = ValidationCheck("Maven tests", "mvn test", CheckOutcome.PASSED, 0, "passed", 100)
        val tools = WorkspaceToolExecutor(object : ValidationRunner {
            override fun validate(workspace: Path) = check
        }, LocalCommandExecutor(), mapper, MicrometerExecutionMetrics(registry))
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

    @Test
    fun `filters file listings by glob and depth`() {
        val root = repository()
        Files.createDirectories(root.resolve("src/main/java"))
        Files.createDirectories(root.resolve("src/test"))
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}")
        Files.writeString(root.resolve("src/test/AppTest.java"), "class AppTest {}")
        Files.writeString(root.resolve("src/README.md"), "readme")
        val tools = executor()

        val shallow = tools.execute("list_files", mapper.readTree("""{"path":"src","depth":1}"""), root).content
        assertTrue(shallow.contains("src/README.md"))
        assertTrue(!shallow.contains("App.java"))

        val globbed = tools.execute("list_files", mapper.readTree("""{"path":"src","glob":"**/*.java"}"""), root).content
        assertTrue(globbed.contains("src/main/java/App.java"))
        assertTrue(globbed.contains("src/test/AppTest.java"))
        assertTrue(!globbed.contains("README.md"))

        val capped = tools.execute("list_files", mapper.readTree("""{"path":"src","maxResults":1}"""), root).content
        assertTrue(capped.startsWith("(showing first 1 files)"))

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("list_files", mapper.readTree("""{"path":"src","glob":"["}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("list_files", mapper.readTree("""{"path":"src","depth":0}"""), root)
        }
    }

    @Test
    fun `reads a line range and reports its bounds`() {
        val root = repository()
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Lines.kt"), (1..100).joinToString("\n") { "line $it" } + "\n")
        val tools = executor()

        val ranged = tools.execute(
            "read_file",
            mapper.readTree("""{"path":"src/Lines.kt","startLine":48,"endLine":50}"""),
            root
        ).content
        assertTrue(ranged.startsWith("Lines 48-50 of 100 in src/Lines.kt:"))
        assertEquals("line 48\nline 49\nline 50", ranged.substringAfter('\n'))

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("read_file", mapper.readTree("""{"path":"src/Lines.kt","startLine":101}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("read_file", mapper.readTree("""{"path":"src/Lines.kt","startLine":10,"endLine":9}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("read_file", mapper.readTree("""{"path":"src/Lines.kt","startLine":"ten"}"""), root)
        }
        val wholeFile = tools.execute(
            "read_file",
            mapper.readTree("""{"path":"src/Lines.kt","startLine":null}"""),
            root
        ).content
        assertTrue(wholeFile.startsWith("line 1\nline 2"))
    }

    @Test
    fun `searches with a regex, a glob and pagination`() {
        val root = repository()
        Files.createDirectories(root.resolve("src/main"))
        Files.createDirectories(root.resolve("src/test"))
        Files.writeString(root.resolve("src/main/Alpha.kt"), "val first = 1\nval second = 2\n")
        Files.writeString(root.resolve("src/main/Beta.java"), "int third = 3;\n")
        Files.writeString(root.resolve("src/test/AlphaTest.kt"), "val tested = 4\n")
        val tools = executor()

        val regex = tools.execute(
            "search_code",
            mapper.readTree("""{"query":"val (first|second)","regex":true,"path":"src"}"""),
            root
        ).content
        assertTrue(regex.contains("src/main/Alpha.kt:1: val first = 1"))
        assertTrue(regex.contains("src/main/Alpha.kt:2: val second = 2"))
        assertTrue(!regex.contains("tested"))

        val globbed = tools.execute(
            "search_code",
            mapper.readTree("""{"query":"val","glob":"**/*.kt","path":"src"}"""),
            root
        ).content
        assertTrue(globbed.contains("AlphaTest.kt"))
        assertTrue(!globbed.contains("Beta.java"))

        val paged = tools.execute(
            "search_code",
            mapper.readTree("""{"query":"val","path":"src","maxResults":1,"offset":1}"""),
            root
        ).content.lines()
        assertTrue(paged.first().contains("Alpha.kt:2"))
        assertTrue(paged.last().startsWith("(showing matches 2-2; continue with offset=2)"))

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("search_code", mapper.readTree("""{"query":"([","regex":true}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("search_code", mapper.readTree("""{"query":"x","glob":"["}"""), root)
        }

        val caseSensitive = tools.execute(
            "search_code",
            mapper.readTree("""{"query":"VAL","ignoreCase":false,"path":"src"}"""),
            root
        ).content
        assertTrue(caseSensitive.startsWith("No matches found"))

        val past = tools.execute(
            "search_code",
            mapper.readTree("""{"query":"val","offset":50}"""),
            root
        ).content
        assertTrue(past.startsWith("No matches at offset 50"))

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("search_code", mapper.readTree("""{"query":"val","path":"src/main/Alpha.kt"}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("search_code", mapper.readTree("""{"query":"val","offset":-1}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("search_code", mapper.readTree("""{"query":"val","offset":1000}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("search_code", mapper.createObjectNode().put("query", "v".repeat(201)), root)
        }
    }

    @Test
    fun `enforces file count and search limits while skipping oversized files`() {
        val root = repository()
        repeat(251) { index ->
            Files.writeString(root.resolve("file-%03d.txt".format(index)), "ordinary file")
        }
        val listed = executor().execute("list_files", mapper.createObjectNode(), root).content
        assertTrue(listed.startsWith("(showing first 250 files)"))
        assertEquals(250, listed.lineSequence().drop(1).count())

        val searchRoot = Files.createDirectory(root.resolve("search"))
        Files.writeString(searchRoot.resolve("a-large.txt"), "match\n".repeat(50_000))
        Files.writeString(searchRoot.resolve("b-matches.txt"), "MATCH\n".repeat(30))
        val matches = executor().execute(
            "search_code",
            mapper.readTree("""{"query":"match","path":"search"}"""),
            root
        ).content.lines()
        assertEquals(26, matches.size)
        assertTrue(matches.dropLast(1).all { it.endsWith(": MATCH") })
        assertTrue(matches.last().startsWith("(showing matches 1-25; continue with offset=25)"))

        Files.writeString(root.resolve("read-limit.txt"), "x".repeat(20_000))
        val truncated = executor().execute(
            "read_file", mapper.readTree("""{"path":"read-limit.txt"}"""), root
        ).content
        assertTrue(truncated.length <= 12_000)
        assertTrue(truncated.endsWith("[truncated at 12000 characters; request a smaller line range]"))
    }

    @Test
    fun `rejects malformed paths and patch file lists outside tool bounds`() {
        val root = repository()
        val tools = executor()
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("read_file", mapper.createObjectNode().put("path", "\u0000"), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("read_file", mapper.createObjectNode().put("path", root.resolve("README.md").toString()), root)
        }

        val tooManyFiles = buildString {
            repeat(11) { index ->
                append("--- a/file-$index.txt\n+++ b/file-$index.txt\n")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode().put("patch", tooManyFiles), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute(
                "apply_patch",
                mapper.createObjectNode().put("patch", "--- /dev/null\n+++ /dev/null\n"),
                root
            )
        }
    }

    @Test
    fun `reports patch application failure after a successful dry run`() {
        val root = repository()
        val commands = object : CommandExecutor {
            override fun run(
                command: List<String>, workingDirectory: Path?, standardInput: String?, timeout: Duration,
                maxOutputChars: Int, environment: Map<String, String>
            ) = if (command.contains("--check")) CommandResult(0, "") else CommandResult(1, "write failed")
        }
        val tools = WorkspaceToolExecutor(object : ValidationRunner {
            override fun validate(workspace: Path) = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED)
        }, commands, mapper, MicrometerExecutionMetrics(registry))
        val patch = """--- a/README.md
+++ b/README.md
@@ -1 +1 @@
-initial
+updated
"""

        val failure = assertThrows(IllegalStateException::class.java) {
            tools.execute("apply_patch", mapper.createObjectNode().put("patch", patch), root)
        }
        assertTrue(failure.message.orEmpty().contains("Patch application failed: write failed"))
    }

    @Test
    fun `finds tests that reference the changed files`() {
        val root = repository()
        Files.createDirectories(root.resolve("src/main"))
        Files.createDirectories(root.resolve("src/test"))
        Files.writeString(root.resolve("src/main/FooService.kt"), "class FooService { fun run() = 1 }\n")
        Files.writeString(
            root.resolve("src/test/FooServiceTest.kt"),
            "class FooServiceTest { private val service = FooService() }\n"
        )
        val git = LocalCommandExecutor()
        check(git.run(listOf("git", "add", "--all"), root, timeout = Duration.ofSeconds(20)).exitCode == 0)
        check(
            git.run(
                listOf("git", "commit", "-m", "add service and test"),
                root,
                timeout = Duration.ofSeconds(20)
            ).exitCode == 0
        )
        Files.writeString(root.resolve("src/main/FooService.kt"), "class FooService { fun run() = 2 }\n")
        val tools = executor()

        val changed = tools.execute("related_tests", mapper.createObjectNode(), root)
        assertTrue(changed.content.contains("Tests that reference the changed types"))
        assertTrue(changed.content.contains("src/test/FooServiceTest.kt - references FooService"))

        val explicit = tools.execute(
            "related_tests",
            mapper.readTree("""{"paths":["src/main/FooService.kt"],"maxResults":1}"""),
            root
        )
        assertTrue(explicit.content.contains("src/test/FooServiceTest.kt"))

        val none = tools.execute(
            "related_tests",
            mapper.readTree("""{"paths":["src/main/Absent.kt"]}"""),
            root
        )
        assertTrue(none.content.startsWith("No test file references"))

        Files.writeString(root.resolve("src/main/Controller.kt"), "class Controller(private val s: FooService)\n")
        Files.writeString(root.resolve("src/test/ControllerTest.kt"), "class ControllerTest { lateinit var c: Controller }\n")
        val transitive = tools.execute(
            "related_tests",
            mapper.readTree("""{"paths":["src/main/FooService.kt"]}"""),
            root
        ).content
        assertTrue(transitive.contains("src/test/FooServiceTest.kt - references FooService"))
        assertTrue(transitive.contains("src/test/ControllerTest.kt - references Controller (hop 2)"))

        val many = tools.execute(
            "related_tests",
            mapper.readTree("""{"paths":["a.kt","b.kt","c.kt","d.kt","e.kt","f.kt"]}"""),
            root
        ).content
        assertTrue(many.startsWith("No test file references the types in 6 changed file(s): "))
        assertTrue(many.trimEnd().endsWith(", ..."))

        val clean = repository()
        assertThrows(IllegalArgumentException::class.java) {
            executor().execute("related_tests", mapper.readTree("""{"paths":[""]}"""), clean)
        }

        // Explicit JSON null takes the default branch rather than a validation error.
        assertTrue(
            tools.execute("related_tests", mapper.readTree("""{"paths":null,"maxResults":null}"""), root)
                .content.contains("FooServiceTest.kt")
        )
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("related_tests", mapper.readTree("""{"maxResults":"many"}"""), root)
        }

        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("related_tests", mapper.readTree("""{"paths":"src/main/FooService.kt"}"""), root)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.execute("related_tests", mapper.readTree("""{"paths":[1]}"""), root)
        }
    }

    private fun executor() = WorkspaceToolExecutor(object : ValidationRunner {
        override fun validate(workspace: Path) = ValidationCheck("Maven tests", outcome = CheckOutcome.PASSED)
    }, LocalCommandExecutor(), mapper, MicrometerExecutionMetrics(registry))

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

package dev.agentplatform.infrastructure.retrieval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class RepositoryMapBuilderTest {
    private lateinit var workspace: Path

    @BeforeEach
    fun createWorkspace() {
        val parent = Path.of("target", "test-workspaces").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        workspace = Files.createTempDirectory(parent, "repo-map-")
    }

    @AfterEach
    fun removeWorkspace() {
        if (Files.exists(workspace)) Files.walk(workspace).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `ranks files that match the task text and shows their symbols`() {
        write("src/main/Greeter.kt", "package sample\n\nclass Greeter {\n    fun farewell(name: String) = \"bye \$name\"\n}\n")
        write("src/main/Greeting.kt", "package sample\n\nclass Greeting { fun message() = \"hi\" }\n")
        write("src/main/Unrelated.kt", "package sample\n\nclass Unrelated { fun compute() = 42 }\n")

        val map = builder().build(workspace, "add a farewell method to the greeter")

        assertTrue(map.startsWith(RepositoryMapBuilder.HEADER))
        assertTrue(map.endsWith(RepositoryMapBuilder.FOOTER))
        assertTrue(map.contains("src/main/Greeter.kt - Greeter"))
        assertTrue(map.indexOf("Greeter.kt") < map.indexOf("Unrelated.kt"))
    }

    @Test
    fun `lifts a central file the task text never names`() {
        write("src/main/Core.kt", "class Core { fun run() = 1 }\n")
        write("src/main/Alpha.kt", "class Alpha { fun use(core: Core) = core.run() }\n")
        write("src/main/Beta.kt", "class Beta { fun use(core: Core) = core.run() }\n")
        write("src/main/Noise.kt", "class Noise { fun other() = 0 }\n")

        val map = RepositoryMapBuilder(maxEntries = 2).build(workspace, "change alpha behaviour")

        assertTrue(map.contains("Alpha.kt"))
        assertTrue(map.contains("Core.kt"))
        assertTrue(!map.contains("Noise.kt"))
    }

    @Test
    fun `honours the entry count and the token budget`() {
        repeat(20) { index -> write("src/File$index.kt", "class File$index { fun value() = $index }\n") }

        val map = builder(maxTokens = 120, maxEntries = 8).build(workspace, "value")

        val entries = map.lines().filter { it.startsWith("  ") }
        assertTrue(entries.size in 1..8)
        assertTrue(RepositoryMapBuilder.estimateTokens(map) <= 120)
    }

    @Test
    fun `skips build output symlinks oversized and binary files`() {
        write("src/main/Real.kt", "class Real { fun value() = 1 }\n")
        write("target/generated/Generated.kt", "class Generated { fun value() = 2 }\n")
        write("node_modules/pkg/Index.kt", "class Packaged { fun value() = 3 }\n")
        Files.write(workspace.resolve("blob.bin"), byteArrayOf(0, 1, 2, 3))
        write("huge.kt", "class Huge { fun value() = 4 }\n".repeat(20_000))

        val map = builder(maxFileBytes = 1_000).build(workspace, "value")

        assertTrue(map.contains("src/main/Real.kt"))
        assertTrue(!map.contains("Generated.kt"))
        assertTrue(!map.contains("Packaged.kt"))
        assertTrue(!map.contains("blob.bin"))
        assertTrue(!map.contains("huge.kt"))
    }

    @Test
    fun `returns nothing when disabled or when there is no indexable text`() {
        write("src/main/Real.kt", "class Real { fun value() = 1 }\n")

        assertEquals("", RepositoryMapBuilder(enabled = false).build(workspace, "real"))
        assertEquals("", builder().build(workspace.resolve("missing"), "real"))
        assertEquals("", builder(maxTokens = 0).build(workspace, "real"))
        assertEquals("", builder(maxEntries = 0).build(workspace, "real"))
        val empty = Files.createDirectory(workspace.resolve("empty"))
        assertEquals("", builder().build(empty, "real"))
        Files.createDirectories(workspace.resolve("empty-binary"))
        Files.write(workspace.resolve("empty-binary/blob.bin"), byteArrayOf(0, 1))
        assertEquals("", builder().build(workspace.resolve("empty-binary"), "binary"))
    }

    @Test
    fun `packs greedily without splitting an entry`() {
        val packed = RepositoryMapBuilder.packByBudget(listOf("aaaaaaa", "b".repeat(400), "c"), 10)

        assertEquals(listOf("aaaaaaa", "c"), packed)
        assertTrue(RepositoryMapBuilder.packByBudget(listOf("a"), 0).isEmpty())
        assertEquals(2, RepositoryMapBuilder.estimateTokens("abcd"))
    }

    @Test
    fun `reads bounds from the application configuration`() {
        write("src/main/Configured.kt", "class Configured { fun value() = 1 }\n")
        val map = RepositoryMapBuilder.fromConfig().build(workspace, "value")
        assertTrue(map.startsWith(RepositoryMapBuilder.HEADER))
    }

    private fun builder(maxTokens: Int = 800, maxEntries: Int = 12, maxFileBytes: Long = 262_144) =
        RepositoryMapBuilder(
            enabled = true,
            maxTokens = maxTokens,
            maxEntries = maxEntries,
            maxFileBytes = maxFileBytes
        )

    private fun write(relative: String, content: String) {
        val file = workspace.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}

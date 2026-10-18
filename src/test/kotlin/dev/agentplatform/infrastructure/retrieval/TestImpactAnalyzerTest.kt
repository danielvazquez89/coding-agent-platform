package dev.agentplatform.infrastructure.retrieval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class TestImpactAnalyzerTest {
    private lateinit var workspace: Path

    @BeforeEach
    fun createWorkspace() {
        val parent = Path.of("target", "test-workspaces").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        workspace = Files.createTempDirectory(parent, "test-impact-")
    }

    @AfterEach
    fun removeWorkspace() {
        if (Files.exists(workspace)) Files.walk(workspace).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `selects a test that references the changed type`() {
        write("src/main/FooService.kt", "class FooService { fun run() = 1 }\n")
        write("src/test/FooServiceTest.kt", "class FooServiceTest { private val service = FooService() }\n")
        write("src/test/UnrelatedTest.kt", "class UnrelatedTest { fun check() = true }\n")

        val selected = TestImpactAnalyzer().select(workspace, listOf("src/main/FooService.kt"), 10)

        assertEquals(listOf("src/test/FooServiceTest.kt"), selected.map { it.path })
        assertEquals(1, selected.single().depth)
        assertEquals("FooService", selected.single().via)
    }

    @Test
    fun `follows one indirection to reach a test`() {
        write("src/main/FooService.kt", "class FooService { fun run() = 1 }\n")
        write("src/main/FooController.kt", "class FooController(private val service: FooService)\n")
        write("src/test/FooControllerTest.kt", "class FooControllerTest { lateinit var controller: FooController }\n")

        val selected = TestImpactAnalyzer().select(workspace, listOf("src/main/FooService.kt"), 10)

        assertEquals(listOf("src/test/FooControllerTest.kt"), selected.map { it.path })
        assertEquals(2, selected.single().depth)
        assertEquals("FooController", selected.single().via)
    }

    @Test
    fun `uses the file stem when the changed file was deleted`() {
        write("src/test/LegacyServiceTest.kt", "class LegacyServiceTest { val service: LegacyService? = null }\n")

        val selected = TestImpactAnalyzer().select(workspace, listOf("src/main/LegacyService.kt"), 10)

        assertEquals(listOf("src/test/LegacyServiceTest.kt"), selected.map { it.path })
    }

    @Test
    fun `recognises tests by directory as well as by name`() {
        write("src/main/Widget.kt", "class Widget { fun value() = 1 }\n")
        write("src/test/kotlin/WidgetChecks.kt", "class WidgetChecks { val widget = Widget() }\n")

        val selected = TestImpactAnalyzer().select(workspace, listOf("src/main/Widget.kt"), 10)

        assertEquals(listOf("src/test/kotlin/WidgetChecks.kt"), selected.map { it.path })
    }

    @Test
    fun `honours the result cap and ignores unrelated or empty changes`() {
        write("src/main/Shared.kt", "class Shared { fun value() = 1 }\n")
        repeat(3) { index -> write("src/test/Shared${index}Test.kt", "class Shared${index}Test { val s = Shared() }\n") }

        assertEquals(2, TestImpactAnalyzer().select(workspace, listOf("src/main/Shared.kt"), 2).size)
        assertTrue(TestImpactAnalyzer().select(workspace, listOf("src/main/Absent.kt"), 5).isEmpty())
        assertTrue(TestImpactAnalyzer().select(workspace, emptyList(), 5).isEmpty())
        assertTrue(TestImpactAnalyzer().select(workspace, listOf("src/main/Shared.kt"), 0).isEmpty())
        assertTrue(TestImpactAnalyzer().select(workspace.resolve("missing"), listOf("src/main/Shared.kt"), 5).isEmpty())
    }

    @Test
    fun `respects a zero depth budget`() {
        write("src/main/FooService.kt", "class FooService { fun run() = 1 }\n")
        write("src/test/FooServiceTest.kt", "class FooServiceTest { private val service = FooService() }\n")

        assertTrue(
            TestImpactAnalyzer(maxDepth = 0).select(workspace, listOf("src/main/FooService.kt"), 10).isEmpty()
        )
    }

    @Test
    fun `ignores files that cannot be read as text`() {
        Files.createDirectories(workspace.resolve("src/main"))
        Files.write(workspace.resolve("src/main/Broken.kt"), byteArrayOf(0xC3.toByte(), 0x28))

        assertTrue(TestImpactAnalyzer().select(workspace, listOf("src/main/Broken.kt"), 5).isEmpty())
    }

    @Test
    fun `classifies test paths, build output and stems`() {
        assertTrue(RepositoryFiles.isTestFile("src/test/kotlin/Foo.kt"))
        assertTrue(RepositoryFiles.isTestFile("src/main/FooTest.kt"))
        assertTrue(RepositoryFiles.isTestFile("src/main/FooIT.java"))
        assertFalse(RepositoryFiles.isTestFile("src/main/Contest.kt"))
        assertTrue(RepositoryFiles.isBuildOutput("target/classes/Foo.class"))
        assertTrue(RepositoryFiles.isBuildOutput("node_modules/pkg/index.js"))
        assertFalse(RepositoryFiles.isBuildOutput("src/main/Foo.kt"))
        assertEquals("Foo", RepositoryFiles.stem("src/main/Foo.kt"))
        assertEquals("README", RepositoryFiles.stem("README"))
    }

    private fun write(relative: String, content: String) {
        val file = workspace.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}

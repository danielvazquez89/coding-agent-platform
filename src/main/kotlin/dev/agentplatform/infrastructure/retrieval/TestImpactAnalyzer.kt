package dev.agentplatform.infrastructure.retrieval

import java.nio.file.Files
import java.nio.file.Path

/**
 * Selects the test files most likely affected by a change, using the same lexical signals as the
 * repository map: the type names a file declares and the type names it mentions.
 *
 * The search is a bounded breadth-first walk. The changed file's declared types (or its file stem
 * when the file was deleted) are looked up among the files that mention them; a test that mentions
 * one of those types is selected, and a non-test file joins the next hop so an indirection such as
 * `FooService` -> `FooController` -> `FooControllerTest` is still found. Depth, file count and size
 * are capped, and nothing is executed.
 */
class TestImpactAnalyzer(
    private val maxFiles: Int = 4_000,
    private val maxFileBytes: Long = 262_144,
    private val maxTotalBytes: Long = 32L * 1024 * 1024,
    private val maxDepth: Int = 3
) {

    data class SelectedTest(val path: String, val depth: Int, val via: String)

    fun select(workspace: Path, changedPaths: List<String>, maxResults: Int): List<SelectedTest> {
        if (maxResults <= 0 || changedPaths.isEmpty() || !Files.isDirectory(workspace)) return emptyList()
        val files = RepositoryFiles.walk(workspace, maxFiles, maxFileBytes, maxTotalBytes)
        val index = files.mapNotNull { file ->
            val text = try {
                Files.readString(file)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            IndexedFile(
                path = RepositoryFiles.relative(workspace, file),
                declared = declaredTypes(text),
                referenced = referencedTypes(text)
            )
        }
        if (index.isEmpty()) return emptyList()
        val byPath = index.associateBy { it.path }
        val referrers = index
            .flatMap { file -> file.referenced.map { it to file.path } }
            .groupBy({ it.first }, { it.second })
        val selected = linkedMapOf<String, SelectedTest>()
        val visited = mutableSetOf<String>()
        var frontier = changedPaths.map(::normalize).filter(String::isNotBlank).toSet()
        visited += frontier
        for (depth in 1..maxDepth) {
            val next = mutableSetOf<String>()
            for (path in frontier) {
                val types = byPath[path]?.declared ?: setOf(RepositoryFiles.stem(path))
                for (type in types) {
                    for (candidate in referrers[type].orEmpty()) {
                        if (candidate in visited) continue
                        if (RepositoryFiles.isTestFile(candidate)) {
                            selected.putIfAbsent(candidate, SelectedTest(candidate, depth, type))
                        } else {
                            next += candidate
                        }
                    }
                }
            }
            if (next.isEmpty()) break
            visited += next
            frontier = next
            if (selected.size >= maxResults) break
        }
        return selected.values
            .sortedWith(compareBy<SelectedTest> { it.depth }.thenBy { it.path })
            .take(maxResults)
    }

    private fun declaredTypes(text: String): Set<String> =
        DECLARATION.findAll(text).map { it.groupValues[1] }.toSet()

    private fun referencedTypes(text: String): Set<String> =
        REFERENCE.findAll(text).map { it.value }.toSet()

    private fun normalize(path: String): String = path.trim().replace('\\', '/').removePrefix("./")

    private data class IndexedFile(val path: String, val declared: Set<String>, val referenced: Set<String>)

    private companion object {
        private val DECLARATION = Regex(
            """\b(?:enum\s+class|class|interface|object|record|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        private val REFERENCE = Regex("""\b[A-Z][A-Za-z0-9_]*\b""")
    }
}

package dev.agentplatform.infrastructure.retrieval

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Shared, bounded repository scanning for the retrieval features. Everything here is read-only and
 * deliberately capped: no walk follows a symlink, enters build output, or reads a binary file.
 */
object RepositoryFiles {

    val IGNORED_DIRECTORIES = setOf(
        ".git", "target", "build", "out", "dist", ".gradle", ".idea", "node_modules", ".venv", "vendor"
    )

    private val TEST_DIRECTORIES = setOf("test", "tests", "androidtest")
    private val TEST_SUFFIXES = listOf("Test.kt", "Tests.kt", "Test.java", "Tests.java", "Spec.kt", "IT.kt", "IT.java")

    fun walk(workspace: Path, maxFiles: Int, maxFileBytes: Long, maxTotalBytes: Long): List<Path> {
        val files = mutableListOf<Path>()
        var bytes = 0L
        Files.walkFileTree(workspace, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (ignored(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (files.size >= maxFiles || bytes >= maxTotalBytes) return FileVisitResult.TERMINATE
                if (!attrs.isRegularFile || Files.isSymbolicLink(file) || attrs.size() > maxFileBytes) {
                    return FileVisitResult.CONTINUE
                }
                if (looksBinary(file)) return FileVisitResult.CONTINUE
                files.add(file)
                bytes += attrs.size()
                return FileVisitResult.CONTINUE
            }
        })
        return files.sortedBy { it.toString() }
    }

    fun looksBinary(file: Path): Boolean {
        if (Files.size(file) == 0L) return false
        return Files.newInputStream(file).use { input ->
            val buffer = ByteArray(minOf(8192L, Files.size(file)).toInt())
            val read = input.read(buffer)
            read > 0 && buffer.copyOf(read).any { it == 0.toByte() }
        }
    }

    fun relative(workspace: Path, file: Path): String =
        workspace.relativize(file).toString().replace('\\', '/')

    fun isBuildOutput(path: String): Boolean = path.split('/').any { it in IGNORED_DIRECTORIES }

    /** A test is a file under a test directory, or one whose name carries a test suffix. */
    fun isTestFile(path: String): Boolean {
        val segments = path.split('/')
        if (segments.dropLast(1).any { it.lowercase() in TEST_DIRECTORIES }) return true
        val fileName = segments.lastOrNull().orEmpty()
        return TEST_SUFFIXES.any { fileName.endsWith(it) }
    }

    fun stem(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')

    private fun ignored(dir: Path): Boolean =
        dir.fileName?.toString() in IGNORED_DIRECTORIES || Files.isSymbolicLink(dir)
}

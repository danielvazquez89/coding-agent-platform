package dev.agentplatform.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.ModelToolDefinition
import dev.agentplatform.domain.port.ToolExecutionResult
import dev.agentplatform.domain.port.ValidationRunner
import jakarta.enterprise.context.ApplicationScoped
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration

@ApplicationScoped
class WorkspaceToolExecutor(
    private val validationRunner: ValidationRunner,
    private val commands: CommandExecutor,
    private val objectMapper: ObjectMapper
) {
    private val codexPatches = CodexPatchNormalizer()

    fun definitions(): List<ModelToolDefinition> = listOf(
        tool("list_files", "List repository files, optionally under a relative directory.", """{"type":"object","properties":{"path":{"type":"string"}},"additionalProperties":false}"""),
        tool("search_code", "Search repository text for a literal query, optionally under a relative directory.", """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"}},"required":["query"],"additionalProperties":false}"""),
        tool("read_file", "Read one small UTF-8 text file by its repository-relative path.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""),
        tool("create_file", "Create a new UTF-8 text file with exactly the content given. Parent directories are created. Fails if the file already exists; use apply_patch to modify an existing file. The path must stay inside the repository.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string","description":"Exact file content. Include the trailing newline if the file should end with one."}},"required":["path","content"],"additionalProperties":false}"""),
        tool("apply_patch", "Apply a patch to repository files. Use either a complete git-compatible unified diff (diff --git, --- and +++ headers, valid hunk ranges) or the Codex envelope (*** Begin Patch, *** Update File:/*** Add File:/*** Delete File:, @@ hunks, *** End Patch). Do not include Markdown fences. Patch paths must stay inside the repository.", """{"type":"object","properties":{"patch":{"type":"string","description":"A git-compatible unified diff or a Codex *** Begin Patch envelope; no Markdown fences."}},"required":["patch"],"additionalProperties":false}"""),
        tool("run_validation", "Run the repository's Maven tests in a disposable, resource-limited Docker container.", """{"type":"object","properties":{},"additionalProperties":false}""")
    )

    fun execute(name: String, arguments: JsonNode, workspace: Path): ToolExecutionResult = when (name) {
        "list_files" -> ToolExecutionResult(listFiles(workspace, arguments.path("path").asText("")))
        "search_code" -> ToolExecutionResult(searchCode(workspace, requiredText(arguments, "query"), arguments.path("path").asText("")))
        "read_file" -> ToolExecutionResult(readFile(workspace, requiredText(arguments, "path")))
        "create_file" -> ToolExecutionResult(
            createFile(workspace, requiredText(arguments, "path"), textArgument(arguments, "content"))
        )
        "apply_patch" -> ToolExecutionResult(applyPatch(workspace, requiredText(arguments, "patch")))
        "run_validation" -> validationRunner.validate(workspace).let { check ->
            ToolExecutionResult("${check.name}: ${check.outcome}. ${check.details.orEmpty()}".take(TOOL_OUTPUT_LIMIT), check)
        }
        else -> throw IllegalArgumentException("Tool '$name' is not allowed")
    }

    private fun listFiles(workspace: Path, relative: String): String {
        val directory = resolve(workspace, relative, allowEmpty = true)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Directory '$relative' does not exist" }
        val files = collectFiles(directory, LIST_FILE_LIMIT)
        val prefix = if (files.size >= LIST_FILE_LIMIT) "(showing first $LIST_FILE_LIMIT files)\n" else ""
        return (prefix + files.joinToString("\n") { workspace.relativize(it).toString().replace('\\', '/') })
            .take(TOOL_OUTPUT_LIMIT).ifBlank { "No files found." }
    }

    private fun searchCode(workspace: Path, query: String, relative: String): String {
        require(query.isNotBlank()) { "Search query must not be blank" }
        val directory = resolve(workspace, relative, allowEmpty = true)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Directory '$relative' does not exist" }
        val matches = mutableListOf<String>()
        for (file in collectFiles(directory, SEARCH_FILE_LIMIT)) {
            if (Files.size(file) > SEARCH_FILE_SIZE_LIMIT) continue
            val lines = try { Files.readAllLines(file) } catch (_: IOException) { continue }
            lines.forEachIndexed { index, line ->
                if (line.contains(query, ignoreCase = true) && matches.size < SEARCH_MATCH_LIMIT) {
                    val path = workspace.relativize(file).toString().replace('\\', '/')
                    matches.add("$path:${index + 1}: ${line.take(300)}")
                }
            }
            if (matches.size == SEARCH_MATCH_LIMIT) break
        }
        return matches.joinToString("\n").take(TOOL_OUTPUT_LIMIT).ifBlank { "No matches found." }
    }

    private fun readFile(workspace: Path, relative: String): String {
        val file = resolve(workspace, relative)
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "File '$relative' does not exist" }
        require(Files.size(file) <= READ_FILE_SIZE_LIMIT) { "File exceeds the ${READ_FILE_SIZE_LIMIT / 1024} KB read limit" }
        val content = Files.readString(file)
        require('\u0000' !in content) { "Binary files cannot be read as text" }
        return content.take(TOOL_OUTPUT_LIMIT)
    }

    private fun createFile(workspace: Path, relative: String, content: String): String {
        require(content.length <= CREATE_FILE_SIZE_LIMIT) {
            "File content exceeds the ${CREATE_FILE_SIZE_LIMIT / 1024} KB limit"
        }
        require('\u0000' !in content) { "Binary content cannot be created through create_file" }
        val file = resolve(workspace, relative)
        require(!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            "File '$relative' already exists; use apply_patch to change it"
        }
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return "Created $relative (${content.length} characters)."
    }

    private fun applyPatch(workspace: Path, patch: String): String {
        require(patch.isNotBlank()) { "Patch must not be blank" }
        require(patch.length <= PATCH_SIZE_LIMIT) { "Patch exceeds the ${PATCH_SIZE_LIMIT / 1024} KB limit" }
        val sanitized = sanitizePatch(patch)
        val normalized = codexPatches.normalize(sanitized, workspace) ?: sanitized
        val paths = normalized.lineSequence().filter { it.startsWith("+++ ") || it.startsWith("--- ") }
            .map { it.substring(4).substringBefore('\t').trim() }
            .filterNot { it == "/dev/null" }
            .map { it.removePrefix("a/").removePrefix("b/") }
            .distinct().toList()
        require(paths.isNotEmpty() && paths.size <= PATCH_FILE_LIMIT) { "Patch must change between 1 and $PATCH_FILE_LIMIT files" }
        paths.forEach { relative -> resolve(workspace, relative) }
        val applyCommand = listOf("git", "-c", "core.autocrlf=false", "apply", "--recount", "--whitespace=nowarn", "-")
        val check = commands.run(
            listOf("git", "-c", "core.autocrlf=false", "apply", "--check", "--recount", "--whitespace=nowarn", "-"),
            workspace,
            normalized,
            timeout = Duration.ofSeconds(20),
            maxOutputChars = 3000
        )
        require(check.exitCode == 0 && !check.timedOut) { "Patch did not apply cleanly: ${check.output.take(2000)}" }
        val applied = commands.run(
            applyCommand,
            workspace,
            normalized,
            timeout = Duration.ofSeconds(20),
            maxOutputChars = 3000
        )
        check(applied.exitCode == 0 && !applied.timedOut) { "Patch application failed: ${applied.output.take(2000)}" }
        return "Patch applied to ${paths.size} file(s): ${paths.joinToString()}."
    }

    private fun sanitizePatch(patch: String): String {
        var text = patch.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (text.startsWith("```")) {
            text = text.lineSequence()
                .drop(1)
                .takeWhile { !it.startsWith("```") }
                .joinToString("\n")
                .trim()
        }
        require(text.isNotBlank()) { "Patch must not be blank" }
        return if (text.endsWith("\n")) text else "$text\n"
    }

    private fun collectFiles(start: Path, limit: Int): List<Path> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir.fileName?.toString() in IGNORED_DIRECTORIES || Files.isSymbolicLink(dir)) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !Files.isSymbolicLink(file)) files.add(file)
                return if (files.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        return files.sortedBy { it.toString() }
    }

    private fun resolve(workspace: Path, relative: String, allowEmpty: Boolean = false): Path {
        val root = workspace.toAbsolutePath().normalize()
        if (relative.isBlank()) {
            require(allowEmpty) { "Repository-relative path is required" }
            return root
        }
        val path = try { Path.of(relative) } catch (_: RuntimeException) {
            throw IllegalArgumentException("Repository-relative path is invalid")
        }
        require(!path.isAbsolute && path.none { it.toString() == ".." }) { "Path must stay inside the repository" }
        val target = root.resolve(path).normalize()
        require(target.startsWith(root)) { "Path must stay inside the repository" }
        var current = root
        for (segment in root.relativize(target)) {
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Symbolic links are not allowed in tool paths" }
        }
        return target
    }

    private fun requiredText(arguments: JsonNode, name: String): String {
        val value = textArgument(arguments, name)
        require(value.isNotBlank()) { "Tool argument '$name' must not be blank" }
        return value
    }

    private fun textArgument(arguments: JsonNode, name: String): String =
        arguments.path(name).takeIf(JsonNode::isTextual)?.asText()
            ?: throw IllegalArgumentException("Tool argument '$name' must be text")

    private fun tool(name: String, description: String, schema: String) =
        ModelToolDefinition(name, description, objectMapper.readTree(schema))

    private companion object {
        const val TOOL_OUTPUT_LIMIT = 12_000
        const val LIST_FILE_LIMIT = 250
        const val SEARCH_FILE_LIMIT = 1000
        const val SEARCH_FILE_SIZE_LIMIT = 256_000L
        const val SEARCH_MATCH_LIMIT = 25
        const val READ_FILE_SIZE_LIMIT = 50_000L
        const val CREATE_FILE_SIZE_LIMIT = 64_000
        const val PATCH_SIZE_LIMIT = 64_000
        const val PATCH_FILE_LIMIT = 10
        val IGNORED_DIRECTORIES = setOf(".git", "target", "build", ".gradle", "node_modules", ".idea")
    }
}

package dev.agentplatform.infrastructure.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.ExecutionMetrics
import dev.agentplatform.domain.port.ModelToolDefinition
import dev.agentplatform.domain.port.ToolExecutionResult
import dev.agentplatform.domain.port.ValidationRunner
import dev.agentplatform.infrastructure.retrieval.RepositoryFiles
import dev.agentplatform.infrastructure.retrieval.RepositoryMapBuilder
import dev.agentplatform.infrastructure.retrieval.TestImpactAnalyzer
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration

@ApplicationScoped
class WorkspaceToolExecutor(
    private val validationRunner: ValidationRunner,
    private val commands: CommandExecutor,
    private val objectMapper: ObjectMapper,
    private val metrics: ExecutionMetrics
) {
    private val log = Logger.getLogger(WorkspaceToolExecutor::class.java)
    private val codexPatches = CodexPatchNormalizer()
    private val repositoryMapBuilder: RepositoryMapBuilder by lazy { RepositoryMapBuilder.fromConfig() }
    private val testImpact = TestImpactAnalyzer()

    /**
     * Ranks repository files for the task prompt. Configuration is read lazily so the bean needs no
     * extra constructor parameter; failures are surfaced to the caller, which decides whether to continue.
     */
    fun repositoryMap(workspace: Path, query: String): String = repositoryMapBuilder.build(workspace, query)

    fun definitions(): List<ModelToolDefinition> = listOf(
        tool("list_files", "List repository files, optionally under a relative directory, filtered by a glob and a depth.", """{"type":"object","properties":{"path":{"type":"string"},"depth":{"type":"integer","minimum":1,"description":"Maximum directory levels below path. Omit for the whole tree."},"glob":{"type":"string","description":"Glob matched against the repository-relative path, for example **/*.kt or src/**/*.java."},"maxResults":{"type":"integer","minimum":1,"description":"Defaults to 250, capped at 1000."}},"additionalProperties":false}"""),
        tool("search_code", "Search repository text line by line. The query is a literal unless regex is true. The path may be a file or a directory. Results are paginated with offset and maxResults.", """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"},"glob":{"type":"string","description":"Glob matched against the repository-relative path, for example **/*.kt."},"regex":{"type":"boolean","description":"Treat the query as a Java regular expression. Invalid expressions are rejected."},"ignoreCase":{"type":"boolean","description":"Defaults to true."},"offset":{"type":"integer","minimum":0,"description":"Number of matches to skip. Defaults to 0."},"maxResults":{"type":"integer","minimum":1,"description":"Defaults to 25, capped at 200."}},"required":["query"],"additionalProperties":false}"""),
        tool("find_symbol", "Locate where a class, interface, enum, record, method, function or field is declared. Use this first when the task names an identifier or when the repository map points at a file: it returns path:line for declarations before other occurrences, so you can read only the surrounding lines. The path may be a file or a directory. A bounded line scan, not a compiler; confirm the line before editing.", """{"type":"object","properties":{"name":{"type":"string","description":"A single identifier, for example writeValue or JSONObject."},"path":{"type":"string"},"glob":{"type":"string","description":"Glob matched against the repository-relative path, for example src/**/*.java."},"maxResults":{"type":"integer","minimum":1,"description":"Defaults to 25, capped at 200."}},"required":["name"],"additionalProperties":false}"""),
        tool("read_file", "Read a UTF-8 text file, optionally a 1-based inclusive line range. A whole-file read is limited to 50 KB; larger files require a range. Output is capped at 12,000 characters.", """{"type":"object","properties":{"path":{"type":"string"},"startLine":{"type":"integer","minimum":1},"endLine":{"type":"integer","minimum":1}},"required":["path"],"additionalProperties":false}"""),
        tool("create_file", "Create a new UTF-8 text file with exactly the content given. Parent directories are created. Fails if the file already exists; use apply_patch to modify an existing file. The path must stay inside the repository.", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string","description":"Exact file content. Include the trailing newline if the file should end with one."}},"required":["path","content"],"additionalProperties":false}"""),
        tool("related_tests", "List the test files that reference types declared in the given paths, using a bounded reference graph. Omit paths to use the files changed in the workspace.", """{"type":"object","properties":{"paths":{"type":"array","items":{"type":"string"},"description":"Repository-relative paths. Omit to use git status."},"maxResults":{"type":"integer","minimum":1,"description":"Defaults to 10, capped at 50."}},"additionalProperties":false}"""),
        tool("apply_patch", "Apply a patch to repository files. Use either a complete git-compatible unified diff (diff --git, --- and +++ headers, valid hunk ranges) or the Codex envelope (*** Begin Patch, *** Update File:/*** Add File:/*** Delete File:, @@ hunks, *** End Patch). Do not include Markdown fences. Patch paths must stay inside the repository.", """{"type":"object","properties":{"patch":{"type":"string","description":"A git-compatible unified diff or a Codex *** Begin Patch envelope; no Markdown fences."}},"required":["patch"],"additionalProperties":false}"""),
        tool("run_validation", "Run the repository's Maven tests in a disposable, resource-limited Docker container.", """{"type":"object","properties":{},"additionalProperties":false}""")
    )

    fun execute(name: String, arguments: JsonNode, workspace: Path): ToolExecutionResult {
        val result = try {
            executeAllowed(name, arguments, workspace)
        } catch (failure: IllegalArgumentException) {
            metrics.toolCall(name, "rejected")
            throw failure
        } catch (failure: Exception) {
            metrics.toolCall(name, "failed")
            throw failure
        }
        metrics.toolCall(name, "success")
        return result
    }

    private fun executeAllowed(name: String, arguments: JsonNode, workspace: Path): ToolExecutionResult = when (name) {
        "list_files" -> ToolExecutionResult(
            listFiles(
                workspace,
                arguments.path("path").asText(""),
                intArgument(arguments, "depth", Int.MAX_VALUE),
                arguments.path("glob").asText(""),
                intArgument(arguments, "maxResults", LIST_FILE_LIMIT)
            )
        )
        "search_code" -> ToolExecutionResult(
            searchCode(
                workspace,
                requiredText(arguments, "query"),
                arguments.path("path").asText(""),
                arguments.path("glob").asText(""),
                arguments.path("regex").asBoolean(false),
                arguments.path("ignoreCase").asBoolean(true),
                intArgument(arguments, "offset", 0),
                intArgument(arguments, "maxResults", SEARCH_MATCH_LIMIT)
            )
        )
        "find_symbol" -> ToolExecutionResult(
            findSymbol(
                workspace,
                requiredText(arguments, "name"),
                arguments.path("path").asText(""),
                arguments.path("glob").asText(""),
                intArgument(arguments, "maxResults", SEARCH_MATCH_LIMIT)
            )
        )
        "read_file" -> ToolExecutionResult(
            readFile(
                workspace,
                requiredText(arguments, "path"),
                optionalIntArgument(arguments, "startLine"),
                optionalIntArgument(arguments, "endLine")
            )
        )
        "create_file" -> ToolExecutionResult(
            createFile(workspace, requiredText(arguments, "path"), textArgument(arguments, "content"))
        )
        "related_tests" -> ToolExecutionResult(
            relatedTests(
                workspace,
                stringListArgument(arguments, "paths"),
                intArgument(arguments, "maxResults", RELATED_TEST_LIMIT)
            )
        )
        "apply_patch" -> ToolExecutionResult(applyPatch(workspace, requiredText(arguments, "patch")))
        "run_validation" -> validationRunner.validate(workspace).let { check ->
            ToolExecutionResult("${check.name}: ${check.outcome}. ${check.details.orEmpty()}".take(TOOL_OUTPUT_LIMIT), check)
        }
        else -> throw IllegalArgumentException("Tool '$name' is not allowed")
    }

    private fun listFiles(workspace: Path, relative: String, depth: Int, glob: String, maxResults: Int): String {
        val directory = resolve(workspace, relative, allowEmpty = true)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Directory '$relative' does not exist" }
        require(depth >= 1) { "Tool argument 'depth' must be at least 1" }
        val limit = maxResults.coerceIn(1, LIST_FILE_LIMIT_MAX)
        val matcher = globMatcher(glob)
        val files = collectFiles(directory, limit, depth) { file -> matchesGlob(matcher, workspace, file) }
        val prefix = if (files.size >= limit) "(showing first $limit files)\n" else ""
        return (prefix + files.joinToString("\n") { workspace.relativize(it).toString().replace('\\', '/') })
            .take(TOOL_OUTPUT_LIMIT).ifBlank { "No files found." }
    }

    private fun searchCode(
        workspace: Path,
        query: String,
        relative: String,
        glob: String,
        regex: Boolean,
        ignoreCase: Boolean,
        offset: Int,
        maxResults: Int
    ): String {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require(query.length <= SEARCH_PATTERN_LIMIT) { "Search query exceeds the $SEARCH_PATTERN_LIMIT character limit" }
        require(offset >= 0) { "Tool argument 'offset' must not be negative" }
        require(offset <= SEARCH_SCAN_MATCH_LIMIT) { "Tool argument 'offset' must not exceed $SEARCH_SCAN_MATCH_LIMIT" }
        val pattern = compileSearchPattern(query, regex, ignoreCase)
        val matcher = globMatcher(glob)
        val limit = maxResults.coerceIn(1, SEARCH_MATCH_LIMIT_MAX)
        val scanLimit = minOf(SEARCH_SCAN_MATCH_LIMIT, offset + limit)
        val matches = mutableListOf<String>()
        var capped = false
        scan@ for (file in scanTargets(workspace, relative, SEARCH_FILE_LIMIT) { matchesGlob(matcher, workspace, it) }) {
            if (Files.size(file) > SEARCH_FILE_SIZE_LIMIT) continue
            val lines = try { Files.readAllLines(file) } catch (_: IOException) { continue }
            val path = workspace.relativize(file).toString().replace('\\', '/')
            for (index in lines.indices) {
                if (matches.size >= scanLimit) { capped = true; break@scan }
                if (pattern.containsMatchIn(lines[index])) matches.add("$path:${index + 1}: ${lines[index].take(300)}")
            }
        }
        if (matches.isEmpty()) return "No matches found."
        if (matches.size <= offset) return "No matches at offset $offset (found ${matches.size})."
        val page = matches.drop(offset).take(limit)
        val footer = if (matches.size > offset + page.size || capped) {
            "\n(showing matches ${offset + 1}-${offset + page.size}; continue with offset=${offset + page.size})"
        } else {
            ""
        }
        return (page.joinToString("\n") + footer).take(TOOL_OUTPUT_LIMIT)
    }

    /**
     * Finds declaration sites for an identifier with a bounded line scan, so a large file can be
     * navigated by symbol instead of read from the top. Declarations are returned before other
     * occurrences, and source files before tests. This is a heuristic, not a compiler: the caller is
     * told to confirm the line, and no repository code is executed.
     */
    private fun findSymbol(workspace: Path, rawName: String, relative: String, glob: String, maxResults: Int): String {
        require(rawName.isNotBlank()) { "Symbol name must not be blank" }
        require(rawName.length <= 200) { "Symbol name must be at most 200 characters" }
        // A model sometimes passes a whole declaration ("static final Writer writeValue") or a qualified
        // name. Search its last identifier token anyway and echo it, so an imprecise call still locates
        // the symbol instead of burning a tool call on a format rule.
        val name = SYMBOL_TOKEN.findAll(rawName).lastOrNull()?.value
            ?: throw IllegalArgumentException("Symbol name '$rawName' contains no identifier; pass one such as 'writeValue'")
        val matcher = globMatcher(glob)
        val limit = maxResults.coerceIn(1, SEARCH_MATCH_LIMIT_MAX)
        val word = Regex("""(^|[^\w$])${Regex.escape(name)}([^\w$]|$)""")
        val declarations = mutableListOf<SymbolHit>()
        val references = mutableListOf<SymbolHit>()
        scan@ for (file in scanTargets(workspace, relative, SEARCH_FILE_LIMIT) { matchesGlob(matcher, workspace, it) }) {
            if (Files.size(file) > SEARCH_FILE_SIZE_LIMIT) continue
            val lines = try { Files.readAllLines(file) } catch (_: IOException) { continue }
            val path = workspace.relativize(file).toString().replace('\\', '/')
            val test = RepositoryFiles.isTestFile(path)
            for (index in lines.indices) {
                val line = lines[index]
                if (!word.containsMatchIn(line)) continue
                val kind = declarationKind(name, line)
                val hit = SymbolHit(path, index + 1, kind, line.trim().take(300), test)
                if (kind == REFERENCE_KIND) {
                    if (references.size < SEARCH_SCAN_MATCH_LIMIT) references += hit
                } else {
                    declarations += hit
                    if (declarations.size >= limit) break@scan
                }
            }
        }
        if (declarations.isEmpty() && references.isEmpty()) return "No declaration or occurrence of '$name' found."
        val ordered = declarations.sortedWith(SYMBOL_HIT_ORDER) + references.sortedWith(SYMBOL_HIT_ORDER)
        val page = ordered.take(limit)
        val header = if (declarations.isEmpty()) {
            "No declaration of '$name' matched the line scan; showing up to $limit occurrence(s) to inspect:"
        } else {
            "Declarations of '$name' (bounded line scan, a heuristic; confirm the line before editing):"
        }
        val body = page.joinToString("\n") { "  ${it.path}:${it.line}: [${it.kind}] ${it.text}" }
        val footer = if (ordered.size > page.size) {
            "\n(showing ${page.size} of ${ordered.size} matches; narrow with path or glob)"
        } else {
            ""
        }
        // The declaration line is trimmed and only a hint: a patch must reproduce the file's exact
        // content, so point the model at read_file before it edits.
        val next = "\nNext: read the lines around a declaration with read_file (startLine/endLine) and copy " +
            "them exactly, including leading whitespace, when you write the patch."
        return (header + "\n" + body + footer + next).take(TOOL_OUTPUT_LIMIT)
    }

    /**
     * Classifies one line that contains the symbol. Conservative on purpose: a line is a declaration
     * only when it carries the syntax a declaration needs, so a call site is reported as a reference
     * rather than pretending to be the definition.
     */
    private fun declarationKind(name: String, line: String): String {
        val symbol = Regex.escape(name)
        val trimmed = line.trim()
        return when {
            Regex("""(^|[^\w$])(class|interface|enum|record|object|trait|struct|typealias)\s+$symbol(\b|<)""")
                .containsMatchIn(trimmed) -> "type"
            Regex("""(^|[^\w$])fun\s+(<[^>]*>\s*)?$symbol\s*\(""").containsMatchIn(trimmed) -> "function"
            Regex(
                """(^|[^\w$])(public|private|protected|static|final|abstract|synchronized|native|default|""" +
                    """override|internal|open|sealed|inline|operator|external|strictfp)\b[^=;{}]*\b$symbol\s*\("""
            ).containsMatchIn(trimmed) -> "method"
            Regex(
                """(^|[^\w$])(public|private|protected|static|final|transient|volatile|val|var|const)\b""" +
                    """[^;=(){}]*\b$symbol\s*(=|;|,|$)"""
            ).containsMatchIn(trimmed) -> "field"
            else -> REFERENCE_KIND
        }
    }

    private data class SymbolHit(val path: String, val line: Int, val kind: String, val text: String, val test: Boolean)

    private fun readFile(workspace: Path, relative: String, startLine: Int?, endLine: Int?): String {
        val file = resolve(workspace, relative)
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "File '$relative' does not exist" }
        val size = Files.size(file)
        require(size <= READ_FILE_MAX_BYTES) { "File exceeds the ${READ_FILE_MAX_BYTES / (1024 * 1024)} MB read limit" }
        require(startLine != null || endLine != null || size <= READ_FILE_SIZE_LIMIT) {
            "File exceeds the ${READ_FILE_SIZE_LIMIT / 1024} KB whole-file read limit; pass startLine and endLine"
        }
        requireUtf8Text(file, relative)
        val total = try {
            Files.lines(file).use { it.count().toInt() }
        } catch (_: IOException) {
            throw IllegalArgumentException("File '$relative' is not readable UTF-8 text")
        }
        val start = (startLine ?: 1).coerceAtLeast(1)
        require(start <= total) { "startLine $start is past the end of '$relative' ($total lines)" }
        val end = (endLine ?: total).coerceAtMost(total)
        require(end >= start) { "endLine $end must not be smaller than startLine $start" }
        val body = try {
            Files.lines(file).use { lines -> lines.skip((start - 1).toLong()).limit((end - start + 1).toLong()).toList() }
        } catch (_: IOException) {
            throw IllegalArgumentException("File '$relative' is not readable UTF-8 text")
        }.joinToString("\n")
        val header = if (startLine != null || endLine != null) "Lines $start-$end of $total in $relative:\n" else ""
        return truncate(header + body, "[truncated at $TOOL_OUTPUT_LIMIT characters; request a smaller line range]")
    }

    private fun requireUtf8Text(file: Path, relative: String) {
        val sample = Files.newInputStream(file).use { input ->
            val buffer = ByteArray(minOf(8192L, Files.size(file)).toInt())
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
        require(sample.none { it == 0.toByte() }) { "Binary file '$relative' cannot be read as text" }
    }

    private fun truncate(text: String, hint: String): String =
        if (text.length <= TOOL_OUTPUT_LIMIT) text
        else text.take(TOOL_OUTPUT_LIMIT - hint.length - 1).trimEnd() + "\n" + hint

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

    private fun relatedTests(workspace: Path, paths: List<String>, maxResults: Int): String {
        val changed = paths.map(String::trim).filter(String::isNotEmpty).ifEmpty { changedFiles(workspace) }
        require(changed.isNotEmpty()) { "No changed files found; pass paths explicitly" }
        val selected = testImpact.select(workspace, changed, maxResults.coerceIn(1, RELATED_TEST_LIMIT_MAX))
        if (selected.isEmpty()) {
            val shown = changed.take(5).joinToString(", ") + if (changed.size > 5) ", ..." else ""
            return "No test file references the types in ${changed.size} changed file(s): $shown"
        }
        return buildString {
            appendLine("Tests that reference the changed types (${selected.size}):")
            selected.forEach { test ->
                val via = if (test.depth == 1) test.via else "${test.via} (hop ${test.depth})"
                appendLine("  ${test.path} - references $via")
            }
            append("Read them before finishing; run_validation still runs the supported suite.")
        }
    }

    private fun changedFiles(workspace: Path): List<String> {
        val tracked = commands.run(
            listOf("git", "diff", "--name-only", "--diff-filter=ACDMRT"),
            workspace,
            timeout = Duration.ofSeconds(20),
            maxOutputChars = 20_000
        )
        val untracked = commands.run(
            listOf("git", "ls-files", "--others", "--exclude-standard"),
            workspace,
            timeout = Duration.ofSeconds(20),
            maxOutputChars = 20_000
        )
        require(tracked.exitCode == 0 && untracked.exitCode == 0) { "Could not list changed repository files" }
        return (tracked.output.lineSequence() + untracked.output.lineSequence())
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(RepositoryFiles::isBuildOutput)
            .distinct()
            .sorted()
            .toList()
    }

    private fun applyPatch(workspace: Path, patch: String): String {
        require(patch.isNotBlank()) { "Patch must not be blank" }
        require(patch.length <= PATCH_SIZE_LIMIT) { "Patch exceeds the ${PATCH_SIZE_LIMIT / 1024} KB limit" }
        val sanitized = sanitizePatch(patch)
        val normalized = codexPatches.normalize(sanitized, workspace) ?: sanitized
        if (codexPatches.isEnvelope(sanitized)) {
            log.infof("apply_patch normalized a Codex envelope (%d characters) to a unified diff", sanitized.length)
        }
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

    private fun collectFiles(
        start: Path,
        limit: Int,
        maxDepth: Int = Int.MAX_VALUE,
        accept: ((Path) -> Boolean)? = null
    ): List<Path> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(start, emptySet(), maxDepth, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir.fileName?.toString() in IGNORED_DIRECTORIES || Files.isSymbolicLink(dir)) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !Files.isSymbolicLink(file) && (accept == null || accept(file))) {
                    files.add(file)
                }
                return if (files.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        })
        return files.sortedBy { it.toString() }
    }

    /**
     * The files a read-only scan visits for a `path` argument. A model naturally scopes a search to the
     * single file it already suspects — live runs show it passing a file path to both `search_code` and
     * `find_symbol` — so a file path scans exactly that file and a directory path walks the tree;
     * anything else fails honestly instead of scanning the wrong thing.
     */
    private fun scanTargets(
        workspace: Path,
        relative: String,
        limit: Int,
        accept: ((Path) -> Boolean)? = null
    ): List<Path> {
        val target = resolve(workspace, relative, allowEmpty = true)
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return if (accept == null || accept(target)) listOf(target) else emptyList()
        }
        require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            "Path '$relative' is not a file or directory in the repository"
        }
        return collectFiles(target, limit, accept = accept)
    }

    private fun globMatcher(glob: String): ((String) -> Boolean)? {
        if (glob.isBlank()) return null
        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$glob")
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Glob '$glob' is invalid")
        }
        return { relative -> matcher.matches(Path.of(relative)) }
    }

    private fun matchesGlob(matcher: ((String) -> Boolean)?, workspace: Path, file: Path): Boolean =
        matcher == null || matcher(workspace.relativize(file).toString().replace('\\', '/'))

    private fun compileSearchPattern(query: String, regex: Boolean, ignoreCase: Boolean): Regex {
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return try {
            if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Search query is not a valid regular expression")
        }
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

    private fun intArgument(arguments: JsonNode, name: String, default: Int): Int {
        val node = arguments.path(name)
        if (node.isMissingNode || node.isNull) return default
        require(node.isIntegralNumber) { "Tool argument '$name' must be an integer" }
        return node.asInt()
    }

    private fun optionalIntArgument(arguments: JsonNode, name: String): Int? {
        val node = arguments.path(name)
        if (node.isMissingNode || node.isNull) return null
        require(node.isIntegralNumber) { "Tool argument '$name' must be an integer" }
        return node.asInt()
    }

    private fun stringListArgument(arguments: JsonNode, name: String): List<String> {
        val node = arguments.path(name)
        if (node.isMissingNode || node.isNull) return emptyList()
        require(node.isArray) { "Tool argument '$name' must be an array of strings" }
        return node.map { element ->
            require(element.isTextual) { "Tool argument '$name' must contain only strings" }
            element.asText()
        }
    }

    private fun tool(name: String, description: String, schema: String) =
        ModelToolDefinition(name, description, objectMapper.readTree(schema))

    private companion object {
        const val TOOL_OUTPUT_LIMIT = 12_000
        const val LIST_FILE_LIMIT = 250
        const val LIST_FILE_LIMIT_MAX = 1000
        const val SEARCH_FILE_LIMIT = 1000
        const val SEARCH_FILE_SIZE_LIMIT = 256_000L
        const val SEARCH_MATCH_LIMIT = 25
        const val SEARCH_MATCH_LIMIT_MAX = 200
        const val SEARCH_SCAN_MATCH_LIMIT = 200
        const val SEARCH_PATTERN_LIMIT = 200
        const val REFERENCE_KIND = "reference"
        val SYMBOL_TOKEN = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        val SYMBOL_HIT_ORDER = compareBy<SymbolHit>({ it.test }, { it.path }, { it.line })
        const val READ_FILE_SIZE_LIMIT = 50_000L
        const val READ_FILE_MAX_BYTES = 10L * 1024 * 1024
        const val CREATE_FILE_SIZE_LIMIT = 64_000
        const val RELATED_TEST_LIMIT = 10
        const val RELATED_TEST_LIMIT_MAX = 50
        const val PATCH_SIZE_LIMIT = 64_000
        const val PATCH_FILE_LIMIT = 10
        val IGNORED_DIRECTORIES = setOf(".git", "target", "build", ".gradle", "node_modules", ".idea")
    }
}

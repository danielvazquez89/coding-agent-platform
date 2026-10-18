package dev.agentplatform.infrastructure.tool

import java.nio.file.Files
import java.nio.file.Path

/**
 * Converts the Codex `apply_patch` envelope (`*** Begin Patch` / `*** Update File:` / `*** End Patch`)
 * into a git unified diff, so the existing path checks, size checks and `git apply` remain the only
 * mutation path.
 *
 * The envelope carries no line numbers, so every update hunk is located by matching its context and
 * removed lines against the file on disk. When a hunk cannot be located exactly the normalizer fails
 * instead of guessing: a patch that cannot be placed is reported, never half-applied.
 *
 * Supported directives: `*** Add File:`, `*** Delete File:`, `*** Update File:`, `@@` hunks and the
 * `*** End of File` marker. `*** Move to:` is rejected: a move must be expressed as an add plus a
 * delete so it cannot silently rewrite a path the model did not name.
 */
class CodexPatchNormalizer {

    /** Returns a unified diff, or `null` when the text is not a Codex envelope. */
    fun normalize(patch: String, workspace: Path): String? {
        val lines = patch.replace("\r\n", "\n").replace("\r", "\n").lines()
        if (lines.none { it.isNotBlank() } || lines.first { it.isNotBlank() }.trim() != BEGIN) return null
        val sections = parse(lines)
        return render(sections, workspace)
    }

    /** True when the text is the Codex envelope rather than a unified diff. */
    fun isEnvelope(patch: String): Boolean {
        val first = patch.replace("\r\n", "\n").replace("\r", "\n").lines().firstOrNull { it.isNotBlank() }
        return first?.trim() == BEGIN
    }

    private fun parse(lines: List<String>): List<Section> {
        val sections = mutableListOf<Section>()
        var current: Section? = null
        var started = false
        var ended = false
        for (raw in lines) {
            if (!started) {
                if (raw.isBlank()) continue
                require(raw.trim() == BEGIN) { "Codex patch must start with $BEGIN" }
                started = true
                continue
            }
            when {
                raw.trim() == END -> {
                    ended = true
                    break
                }
                raw.startsWith(UPDATE) -> current = Update(path(raw, UPDATE)).also { sections += it }
                raw.startsWith(ADD) -> current = Add(path(raw, ADD)).also { sections += it }
                raw.startsWith(DELETE) -> current = Delete(path(raw, DELETE)).also { sections += it }
                raw.startsWith(MOVE) -> {
                    val update = current as? Update
                        ?: throw IllegalArgumentException("'$MOVE' must follow an '$UPDATE' section")
                    throw IllegalArgumentException(
                        "'$MOVE' is not supported; express a move as a '$ADD' plus a '$DELETE'"
                    )
                }
                raw.trim() == END_OF_FILE -> Unit
                raw.startsWith("@@") -> {
                    val update = current as? Update
                        ?: throw IllegalArgumentException("A '@@' hunk must follow an '$UPDATE' section")
                    update.hunks += Hunk()
                }
                raw.startsWith("***") -> throw IllegalArgumentException("Unsupported Codex patch directive: ${raw.trim()}")
                else -> {
                    val section = current
                    if (section == null) {
                        if (raw.isNotBlank()) {
                            throw IllegalArgumentException("Content line outside any Codex patch section: ${raw.trim()}")
                        }
                    } else {
                        section.accept(raw)
                    }
                }
            }
        }
        require(started) { "Codex patch must start with $BEGIN" }
        require(ended) { "Codex patch is missing $END" }
        require(sections.isNotEmpty()) { "Codex patch contains no file sections" }
        return sections
    }

    private fun render(sections: List<Section>, workspace: Path): String = buildString {
        sections.forEach { section ->
            when (section) {
                is Add -> appendAdd(section)
                is Delete -> appendDelete(section, workspace)
                is Update -> appendUpdate(section, workspace)
            }
        }
    }

    private fun StringBuilder.appendAdd(section: Add) {
        require(section.lines.isNotEmpty()) { "Added file '${section.path}' must contain at least one line" }
        appendLine("diff --git a/${section.path} b/${section.path}")
        appendLine("new file mode 100644")
        appendLine("--- /dev/null")
        appendLine("+++ b/${section.path}")
        appendLine("@@ -0,0 +1,${section.lines.size} @@")
        section.lines.forEach { appendLine("+$it") }
    }

    private fun StringBuilder.appendDelete(section: Delete, workspace: Path) {
        val existing = readLines(workspace, section.path)
        appendLine("diff --git a/${section.path} b/${section.path}")
        appendLine("deleted file mode 100644")
        appendLine("--- a/${section.path}")
        appendLine("+++ /dev/null")
        appendLine("@@ -1,${existing.size} +0,0 @@")
        existing.forEach { appendLine("-$it") }
    }

    private fun StringBuilder.appendUpdate(section: Update, workspace: Path) {
        require(section.hunks.isNotEmpty()) { "Updated file '${section.path}' must contain at least one '@@' hunk" }
        val existing = readLines(workspace, section.path)
        val placed = mutableListOf<PlacedHunk>()
        var searchFrom = 0
        section.hunks.forEach { hunk ->
            val oldLines = hunk.lines.filter { it.startsWith(" ") || it.startsWith("-") }.map { it.substring(1) }
            val newLines = hunk.lines.filter { it.startsWith(" ") || it.startsWith("+") }.map { it.substring(1) }
            require(oldLines.isNotEmpty()) {
                "A '@@' hunk for '${section.path}' must include at least one context or removed line, " +
                    "otherwise its position is ambiguous"
            }
            val index = locate(existing, oldLines, searchFrom)
                ?: throw IllegalArgumentException(mismatchMessage(section.path, existing, oldLines))
            placed += PlacedHunk(index, oldLines, newLines, hunk.lines)
            searchFrom = index + oldLines.size
        }
        appendLine("diff --git a/${section.path} b/${section.path}")
        appendLine("--- a/${section.path}")
        appendLine("+++ b/${section.path}")
        var delta = 0
        placed.forEachIndexed { position, hunk ->
            val oldEnd = hunk.index + hunk.oldLines.size
            val nextStart = placed.getOrNull(position + 1)?.index ?: existing.size
            // A hunk with no trailing context is only applied by git when it ends at end of file, so
            // carry real context forward. Never cross into the next hunk.
            val trailing = minOf(CONTEXT_LINES, existing.size - oldEnd, nextStart - oldEnd).coerceAtLeast(0)
            appendLine(
                "@@ -${hunk.index + 1},${hunk.oldLines.size + trailing} " +
                    "+${hunk.index + 1 + delta},${hunk.newLines.size + trailing} @@"
            )
            hunk.body.forEach { appendLine(it) }
            repeat(trailing) { appendLine(" ${existing[oldEnd + it]}") }
            delta += hunk.newLines.size - hunk.oldLines.size
        }
    }

    private data class PlacedHunk(
        val index: Int,
        val oldLines: List<String>,
        val newLines: List<String>,
        val body: List<String>
    )

    private fun locate(existing: List<String>, oldLines: List<String>, from: Int): Int? {
        var index = from
        while (index + oldLines.size <= existing.size) {
            if (existing.subList(index, index + oldLines.size) == oldLines) return index
            index++
        }
        return null
    }

    /**
     * The message for a hunk that cannot be placed. A model that wrote the right change with the wrong
     * leading whitespace gets the actual file content back, so it can correct the hunk instead of
     * re-reading the file; the patch still fails and nothing is applied.
     */
    private fun mismatchMessage(path: String, existing: List<String>, oldLines: List<String>): String {
        val first = oldLines.first().trim().take(120)
        val header = "Codex patch hunk for '$path' does not match the current content. " +
            "Expected ${oldLines.size} line(s) starting with: $first."
        val anchor = existing.indexOfFirst { it.trim() == oldLines.first().trim() }
        val detail = if (anchor >= 0) {
            val end = minOf(existing.size, anchor + oldLines.size)
            val actual = existing.subList(anchor, end).joinToString("\n") { "  |$it" }
            "\nThe file has this at line ${anchor + 1}:\n$actual\n" +
                "Copy those lines exactly, including leading whitespace, into the hunk."
        } else {
            "\nNo line whose text is '$first' exists in the file; use read_file to check the current " +
                "content and rebuild the hunk."
        }
        return header + detail
    }

    private fun readLines(workspace: Path, relative: String): List<String> {
        val root = workspace.toAbsolutePath().normalize()
        val path = try {
            Path.of(relative)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Patch path '$relative' is invalid")
        }
        require(!path.isAbsolute && path.none { it.toString() == ".." }) { "Path must stay inside the repository" }
        val target = root.resolve(path).normalize()
        require(target.startsWith(root)) { "Path must stay inside the repository" }
        require(Files.isRegularFile(target)) {
            "File '$relative' does not exist; use '$ADD' for a new file"
        }
        return Files.readAllLines(target)
    }

    private fun path(line: String, directive: String): String {
        val value = line.removePrefix(directive).trim()
        require(value.isNotEmpty()) { "'$directive' requires a repository-relative path" }
        return value
    }

    private sealed interface Section {
        val path: String
    }

    private class Update(override val path: String) : Section {
        val hunks = mutableListOf<Hunk>()
    }

    private class Add(override val path: String) : Section {
        val lines = mutableListOf<String>()
    }

    private class Delete(override val path: String) : Section

    private inner class Hunk {
        val lines = mutableListOf<String>()

        fun accept(raw: String) {
            lines += when {
                raw.isEmpty() -> " "
                raw.startsWith(" ") || raw.startsWith("+") || raw.startsWith("-") -> raw
                // A context line whose leading space the model omitted. The exact match against the
                // file still decides, so a wrong guess fails instead of corrupting content.
                else -> " $raw"
            }
        }
    }

    private fun Section.accept(raw: String) {
        when (this) {
            is Add -> lines += if (raw.startsWith("+")) raw.substring(1) else raw
            is Update -> (hunks.lastOrNull() ?: throw IllegalArgumentException(
                "Content line for '${path}' appears before its first '@@' hunk"
            )).accept(raw)
            is Delete -> throw IllegalArgumentException("'$DELETE' section for '${path}' must not contain content")
        }
    }

    private companion object {
        const val CONTEXT_LINES = 3
        const val BEGIN = "*** Begin Patch"
        const val END = "*** End Patch"
        const val UPDATE = "*** Update File:"
        const val ADD = "*** Add File:"
        const val DELETE = "*** Delete File:"
        const val MOVE = "*** Move to:"
        const val END_OF_FILE = "*** End of File"
    }
}

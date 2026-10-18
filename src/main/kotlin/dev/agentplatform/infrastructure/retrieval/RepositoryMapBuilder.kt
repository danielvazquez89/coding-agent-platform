package dev.agentplatform.infrastructure.retrieval

import org.eclipse.microprofile.config.ConfigProvider
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.math.ln

/**
 * Builds a compact, ranked map of the repository to seed the task prompt.
 *
 * Ranking is deliberately lexical: BM25 over identifier tokens finds files that match the task text,
 * and PageRank over "file A names the type in file B" edges lifts the structurally central files that
 * the task text never names. Nothing here executes repository code or calls a model.
 *
 * The map is a heuristic, not an index of truth: it is labelled as such in the prompt and the model is
 * told to confirm content with `read_file`/`search_code`. The output is bounded by an entry count and a
 * token budget, and every filesystem walk is capped by file count and total bytes.
 */
class RepositoryMapBuilder(
    private val enabled: Boolean = true,
    private val maxTokens: Int = 800,
    private val maxEntries: Int = 12,
    private val maxIndexedFiles: Int = 4_000,
    private val maxFileBytes: Long = 262_144,
    private val maxIndexedBytes: Long = 32L * 1024 * 1024
) {

    fun build(workspace: Path, query: String): String {
        if (!enabled || maxTokens <= 0 || maxEntries <= 0) return ""
        if (!Files.isDirectory(workspace)) return ""
        val files = collect(workspace)
        if (files.isEmpty()) return ""
        val documents = index(workspace, files)
        if (documents.isEmpty()) return ""
        val bm25 = bm25Scores(tokenize(query), documents)
        val ranks = pageRank(documents)
        val ranked = documents.mapIndexed { index, document ->
            Ranked(document, BM25_WEIGHT * bm25[index] + RANK_WEIGHT * ranks[index])
        }.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.document.path })
        val entries = ranked.take(maxEntries).map { "  ${render(it)}" }
        val budget = maxTokens - estimateTokens(HEADER) - estimateTokens(FOOTER)
        val packed = packByBudget(entries, budget)
        if (packed.isEmpty()) return ""
        return buildString {
            appendLine(HEADER)
            packed.forEach(::appendLine)
            append(FOOTER)
        }
    }

    private fun collect(workspace: Path): List<Path> {
        val files = mutableListOf<Path>()
        var bytes = 0L
        Files.walkFileTree(workspace, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir.fileName?.toString() in IGNORED_DIRECTORIES || Files.isSymbolicLink(dir)) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (files.size >= maxIndexedFiles || bytes >= maxIndexedBytes) return FileVisitResult.TERMINATE
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

    private fun index(workspace: Path, files: List<Path>): List<Document> = files.mapNotNull { file ->
        val text = try {
            Files.readString(file)
        } catch (_: Exception) {
            return@mapNotNull null
        }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return@mapNotNull null
        Document(
            path = workspace.relativize(file).toString().replace('\\', '/'),
            frequencies = tokens.groupingBy { it }.eachCount(),
            length = tokens.size,
            symbols = symbols(text),
            referencedNames = REFERENCED.findAll(text).map { it.value }.toSet()
        )
    }

    private fun bm25Scores(queryTerms: List<String>, documents: List<Document>): List<Double> {
        if (queryTerms.isEmpty() || documents.isEmpty()) return List(documents.size) { 0.0 }
        val terms = queryTerms.distinct()
        val averageLength = documents.sumOf { it.length }.toDouble() / documents.size
        val normalizer = if (averageLength <= 0.0) 1.0 else averageLength
        val documentFrequency = terms.associateWith { term -> documents.count { it.frequencies.containsKey(term) } }
        val raw = DoubleArray(documents.size)
        documents.forEachIndexed { index, document ->
            var score = 0.0
            for (term in terms) {
                val frequency = document.frequencies[term] ?: 0
                if (frequency == 0) continue
                val frequencyInCorpus = documentFrequency.getValue(term)
                val idf = ln(1.0 + (documents.size - frequencyInCorpus + 0.5) / (frequencyInCorpus + 0.5))
                score += idf * (frequency * (BM25_K1 + 1)) /
                    (frequency + BM25_K1 * (1 - BM25_B + BM25_B * document.length / normalizer))
            }
            raw[index] = score
        }
        val max = raw.maxOrNull() ?: 0.0
        return raw.map { if (max > 0.0) it / max else 0.0 }
    }

    private fun pageRank(documents: List<Document>): List<Double> {
        val count = documents.size
        if (count == 0) return emptyList()
        val byStem = documents.withIndex().groupBy({ stem(it.value.path) }, { it.index })
        val outgoing = documents.mapIndexed { index, document ->
            document.referencedNames.mapNotNull { byStem[it] }.flatten().filter { it != index }.toSet()
        }
        val rank = DoubleArray(count) { 1.0 / count }
        repeat(RANK_ITERATIONS) {
            val next = DoubleArray(count) { (1 - RANK_DAMPING) / count }
            var dangling = 0.0
            for (index in 0 until count) {
                val targets = outgoing[index]
                if (targets.isEmpty()) {
                    dangling += rank[index]
                    continue
                }
                val share = RANK_DAMPING * rank[index] / targets.size
                targets.forEach { next[it] += share }
            }
            val danglingShare = RANK_DAMPING * dangling / count
            for (index in 0 until count) {
                next[index] += danglingShare
                rank[index] = next[index]
            }
        }
        val max = rank.maxOrNull() ?: 0.0
        return rank.map { if (max > 0.0) it / max else 0.0 }
    }

    private fun render(entry: Ranked): String =
        if (entry.document.symbols.isEmpty()) {
            entry.document.path
        } else {
            "${entry.document.path} - ${entry.document.symbols.joinToString(", ")}"
        }

    private fun looksBinary(file: Path): Boolean {
        if (Files.size(file) == 0L) return false
        return Files.newInputStream(file).use { input ->
            val buffer = ByteArray(minOf(8192L, Files.size(file)).toInt())
            val read = input.read(buffer)
            read > 0 && buffer.copyOf(read).any { it == 0.toByte() }
        }
    }

    private data class Document(
        val path: String,
        val frequencies: Map<String, Int>,
        val length: Int,
        val symbols: List<String>,
        val referencedNames: Set<String>
    )

    private data class Ranked(val document: Document, val score: Double)

    companion object {
        const val HEADER = "Repository map (ranked from the task text; a heuristic, not exhaustive):"
        const val FOOTER = "Use read_file and search_code to confirm content before changing anything."

        private const val BM25_K1 = 1.2
        private const val BM25_B = 0.75
        private const val BM25_WEIGHT = 0.7
        private const val RANK_WEIGHT = 0.3
        private const val RANK_DAMPING = 0.85
        private const val RANK_ITERATIONS = 20
        private const val SYMBOL_LIMIT = 6

        private val WORD = Regex("[A-Za-z][A-Za-z0-9_]*")
        private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")
        private val SYMBOL = Regex(
            """\b(?:enum\s+class|class|interface|object|record|fun|typealias|void)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        private val REFERENCED = Regex("""\b[A-Z][A-Za-z0-9_]*\b""")

        private val IGNORED_DIRECTORIES = setOf(
            ".git", "target", "build", "out", "dist", ".gradle", ".idea", "node_modules", ".venv", "vendor"
        )

        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "then", "than", "there", "their",
            "should", "must", "would", "could", "about", "after", "before", "when", "where", "which", "while",
            "have", "has", "not", "are", "was", "were", "will", "can", "all", "any", "but", "you", "your",
            "our", "its", "use", "using", "make", "made", "add", "adds", "new", "one", "two", "also", "each",
            "val", "var", "fun", "class", "return", "import", "package", "public", "private", "protected",
            "internal", "static", "final", "void", "new", "null", "true", "false", "if", "else", "when",
            "is", "in", "of", "to", "a", "an", "be", "by", "on", "or", "as", "at", "it"
        )

        /** Rough prompt cost: four characters per token plus one, with no tokenizer dependency. */
        fun estimateTokens(text: String): Int = (text.length + 3) / 4 + 1

        /** Greedy packer: keeps rank order, skips an entry that does not fit, and never splits one. */
        fun packByBudget(items: List<String>, budgetTokens: Int): List<String> {
            if (budgetTokens <= 0) return emptyList()
            val packed = mutableListOf<String>()
            var used = 0
            for (item in items) {
                val cost = estimateTokens(item)
                if (used + cost > budgetTokens) continue
                packed.add(item)
                used += cost
            }
            return packed
        }

        fun fromConfig(): RepositoryMapBuilder {
            val config = ConfigProvider.getConfig()
            fun text(name: String, default: String): String =
                config.getOptionalValue(name, String::class.java)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .orElse(default)

            fun integer(name: String, default: Int): Int =
                text(name, default.toString()).toIntOrNull()
                    ?: throw IllegalArgumentException("Configuration '$name' must be an integer")

            fun long(name: String, default: Long): Long =
                text(name, default.toString()).toLongOrNull()
                    ?: throw IllegalArgumentException("Configuration '$name' must be an integer")

            fun bool(name: String, default: Boolean): Boolean =
                text(name, default.toString()).toBooleanStrictOrNull()
                    ?: throw IllegalArgumentException("Configuration '$name' must be true or false")

            return RepositoryMapBuilder(
                enabled = bool("agent.context.repo-map.enabled", true),
                maxTokens = integer("agent.context.repo-map.max-tokens", 800),
                maxEntries = integer("agent.context.repo-map.max-entries", 12),
                maxIndexedFiles = integer("agent.context.repo-map.max-indexed-files", 4_000),
                maxFileBytes = long("agent.context.repo-map.max-file-bytes", 262_144)
            )
        }

        private fun tokenize(text: String): List<String> =
            WORD.findAll(text)
                .flatMap { identifierParts(it.value) }
                .map { it.lowercase() }
                .filter { it.length >= 2 && it !in STOPWORDS }
                .toList()

        private fun identifierParts(raw: String): List<String> =
            raw.split('_').flatMap { part -> CAMEL.split(part) }.filter { it.isNotBlank() }

        private fun symbols(text: String): List<String> =
            SYMBOL.findAll(text).map { it.groupValues[1] }.distinct().take(SYMBOL_LIMIT).toList()

        private fun stem(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')
    }
}

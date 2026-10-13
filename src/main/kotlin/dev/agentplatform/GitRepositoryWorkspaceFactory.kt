package dev.agentplatform

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration

@ApplicationScoped
class GitRepositoryWorkspaceFactory(
    private val commands: CommandExecutor,
    @ConfigProperty(name = "agent.execution.allowed-local-root", defaultValue = "")
    private val allowedLocalRoot: String,
    @ConfigProperty(name = "agent.execution.workspace-root", defaultValue = "${'$'}{java.io.tmpdir}/coding-agent-platform/workspaces")
    private val configuredWorkspaceRoot: String
) : RepositoryWorkspaceFactory {
    override fun open(repositoryUrl: String): RepositoryWorkspace {
        require(repositoryUrl.isNotBlank()) { "A repository URL or local path is required" }
        val source = validateSource(repositoryUrl)
        val workspaceRoot = Path.of(configuredWorkspaceRoot).toAbsolutePath().normalize()
        Files.createDirectories(workspaceRoot)
        val cloneDirectory = Files.createTempDirectory(workspaceRoot, "attempt-")
        try {
            if (source is RepositorySource.Local) {
                copyLocalRepository(Path.of(source.value), cloneDirectory)
                initializeWorkspaceGit(cloneDirectory)
            } else {
                val result = commands.run(
                    listOf("git", "-c", "credential.helper=", "clone", "--depth", "1", "--no-tags", "--no-recurse-submodules", "--", source.value, cloneDirectory.toString()),
                    timeout = Duration.ofMinutes(3),
                    maxOutputChars = 8_000,
                    environment = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_CONFIG_NOSYSTEM" to "1")
                )
                check(!result.timedOut && result.exitCode == 0) {
                    "Could not clone repository: ${result.output.take(2000).ifBlank { "git exited with ${result.exitCode}" }}"
                }
            }
            val verification = commands.run(listOf("git", "rev-parse", "--is-inside-work-tree"), cloneDirectory)
            check(verification.exitCode == 0 && verification.output.trim() == "true") {
                "Cloned directory is not a Git working tree"
            }
            return GitRepositoryWorkspace(cloneDirectory, commands)
        } catch (failure: Exception) {
            deleteTree(cloneDirectory)
            throw failure
        }
    }

    private fun validateSource(input: String): RepositorySource {
        if (input.startsWith("https://", ignoreCase = true)) {
            val uri = try { java.net.URI(input) } catch (_: java.net.URISyntaxException) {
                throw IllegalArgumentException("Repository URL is invalid")
            }
            require(uri.scheme.equals("https", true) && uri.host?.equals("github.com", true) == true) {
                "Remote repositories must use an HTTPS GitHub URL"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Repository URL must not contain credentials, query parameters, or fragments"
            }
            require(Regex("^/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?/?$").matches(uri.path)) {
                "GitHub repository URL must identify exactly one owner and repository"
            }
            return RepositorySource.Remote(uri.toASCIIString().trimEnd('/'))
        }
        val root = allowedLocalRoot.takeIf(String::isNotBlank)?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: throw IllegalArgumentException("Local repositories are disabled; configure agent.execution.allowed-local-root")
        val path = try { Path.of(input).toAbsolutePath().normalize() } catch (_: RuntimeException) {
            throw IllegalArgumentException("Local repository path is invalid")
        }
        require(path.startsWith(root) && path != root) { "Local repository must be inside the configured allowed root" }
        require(!Files.isSymbolicLink(path)) { "Local repository path must not be a symbolic link" }
        require(Files.isDirectory(path) && Files.exists(path.resolve(".git"))) {
            "Local repository must be an existing Git working tree inside the allowed root"
        }
        val realRoot = root.toRealPath()
        val realPath = path.toRealPath()
        require(realPath.startsWith(realRoot) && realPath != realRoot) { "Local repository must resolve inside the configured allowed root" }
        return RepositorySource.Local(realPath.toString())
    }

    private fun copyLocalRepository(source: Path, destination: Path) {
        var copiedBytes = 0L
        val listing = commands.run(
            listOf("git", "-C", source.toString(), "ls-files", "--cached", "--others", "--exclude-standard", "-z"),
            timeout = Duration.ofSeconds(30),
            maxOutputChars = MAX_LOCAL_INDEX_CHARS,
            environment = mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_TERMINAL_PROMPT" to "0")
        )
        check(!listing.timedOut && listing.exitCode == 0) { "Could not list source repository files: ${listing.output.take(2000)}" }
        require(!listing.output.endsWith("[output truncated]")) { "Local repository file list exceeds the supported size" }
        val files = listing.output.split('\u0000').filter(String::isNotEmpty).distinct()
        require(files.size <= MAX_LOCAL_FILES) { "Local repository exceeds the $MAX_LOCAL_FILES file limit" }
        for (relative in files) {
            val sourceFile = source.resolve(relative).normalize()
            require(sourceFile.startsWith(source) && !Files.isSymbolicLink(sourceFile)) {
                "Local repository contains an unsafe symbolic link or path"
            }
            if (isSensitiveFile(relative)) continue
            if (!Files.isRegularFile(sourceFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
            copiedBytes += Files.size(sourceFile)
            require(copiedBytes <= MAX_LOCAL_BYTES) { "Local repository exceeds the ${MAX_LOCAL_BYTES / (1024 * 1024)} MB copy limit" }
            val destinationFile = destination.resolve(relative).normalize()
            require(destinationFile.startsWith(destination)) { "Repository path must stay inside the workspace" }
            Files.createDirectories(destinationFile.parent)
            Files.copy(sourceFile, destinationFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isSensitiveFile(relative: String): Boolean {
        val normalized = relative.replace('\\', '/').lowercase()
        val fileName = normalized.substringAfterLast('/')
        return fileName == ".env" || (fileName.startsWith(".env.") && fileName !in setOf(".env.example", ".env.sample", ".env.template")) ||
            fileName.endsWith(".pem") || fileName.endsWith(".key") || fileName in setOf("id_rsa", "id_ed25519", "credentials.json") ||
            normalized.startsWith(".aws/") || normalized.startsWith(".ssh/")
    }

    private fun initializeWorkspaceGit(root: Path) {
        val globalConfig = Files.createTempFile(root.parent, "agent-git-config-", ".config")
        try {
            val environment = mapOf(
                "GIT_CONFIG_GLOBAL" to globalConfig.toString(),
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_TERMINAL_PROMPT" to "0"
            )
            runGit(listOf("git", "init", "--initial-branch=main"), root, environment)
            runGit(listOf("git", "add", "--all"), root, environment)
            runGit(
                listOf("git", "-c", "user.name=Coding Agent", "-c", "user.email=agent@localhost.invalid", "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "Workspace baseline"),
                root,
                environment
            )
        } finally {
            Files.deleteIfExists(globalConfig)
        }
    }

    private fun runGit(command: List<String>, root: Path, environment: Map<String, String>) {
        val result = commands.run(command, root, timeout = Duration.ofMinutes(2), maxOutputChars = 8_000, environment = environment)
        check(!result.timedOut && result.exitCode == 0) {
            "Could not prepare isolated repository workspace: ${result.output.take(2000)}"
        }
    }

    private sealed interface RepositorySource {
        val value: String
        data class Local(override val value: String) : RepositorySource
        data class Remote(override val value: String) : RepositorySource
    }

    private class GitRepositoryWorkspace(
        override val root: Path,
        private val commands: CommandExecutor
    ) : RepositoryWorkspace {
        override fun changedFiles(): List<String> {
            val tracked = commands.run(listOf("git", "diff", "--name-only", "--diff-filter=ACDMRT"), root)
            val untracked = commands.run(listOf("git", "ls-files", "--others", "--exclude-standard"), root)
            check(tracked.exitCode == 0 && untracked.exitCode == 0) { "Could not list changed repository files" }
            return (tracked.output.lineSequence() + untracked.output.lineSequence())
                .map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()
        }

        override fun patchDiff(maxChars: Int): String {
            require(maxChars > 0) { "Diff output limit must be positive" }
            val tracked = commands.run(listOf("git", "diff", "--no-ext-diff", "--unified=3", "--"), root, maxOutputChars = maxChars)
            check(tracked.exitCode == 0) { "Could not create repository diff" }
            val diff = tracked.output.toMutableStringBuilder(maxChars)
            val untracked = commands.run(listOf("git", "ls-files", "--others", "--exclude-standard"), root)
            check(untracked.exitCode == 0) { "Could not list untracked repository files" }
            for (file in untracked.output.lineSequence().map(String::trim).filter(String::isNotEmpty)) {
                val newFile = commands.run(listOf("git", "diff", "--no-index", "--no-ext-diff", "--unified=3", "--", "/dev/null", file), root, maxOutputChars = maxChars)
                if (newFile.exitCode in 0..1 && diff.length < maxChars) diff.append(newFile.output.take(maxChars - diff.length))
            }
            return diff.toString().take(maxChars)
        }

        override fun close() = deleteTree(root)
    }

    companion object {
        private const val MAX_LOCAL_FILES = 100_000
        private const val MAX_LOCAL_BYTES = 1_073_741_824L
        private const val MAX_LOCAL_INDEX_CHARS = 20_000_000
        private fun String.toMutableStringBuilder(maxChars: Int) = StringBuilder(take(maxChars))

        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    file.toFile().setWritable(true)
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}


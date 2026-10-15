package dev.agentplatform.infrastructure.github

import com.fasterxml.jackson.databind.ObjectMapper
import dev.agentplatform.domain.model.PullRequestReference
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.PullRequestPublisher
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

@ApplicationScoped
class GitHubPullRequestPublisher(
    private val commands: CommandExecutor,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "agent.github.token", defaultValue = "")
    private val token: String,
    @ConfigProperty(name = "agent.github.allowed-repositories", defaultValue = "")
    allowedRepositories: String,
    @ConfigProperty(name = "agent.github.api-url", defaultValue = "https://api.github.com")
    private val apiUrl: String,
    @ConfigProperty(name = "agent.github.pull-requests.enabled", defaultValue = "false")
    private val enabled: Boolean
) : PullRequestPublisher {
    private val allowedRepositories = allowedRepositories.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { it.lowercase() }
        .toSet()

    private val httpClient by lazy {
        HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()
    }

    init {
        val api = parseApiUrl(apiUrl)
        require(api.scheme.equals("https", ignoreCase = true) || isLoopback(api.host)) {
            "GitHub API URL must use HTTPS except for loopback test servers"
        }
        require(allowedRepositories.all(REPOSITORY_NAME::matches)) {
            "agent.github.allowed-repositories must contain owner/repository names"
        }
        require(!enabled || token.isNotBlank()) {
            "GitHub pull-request publishing is enabled but AGENT_GITHUB_TOKEN is not configured"
        }
        require(!enabled || allowedRepositories.isNotEmpty()) {
            "GitHub pull-request publishing is enabled but AGENT_GITHUB_ALLOWED_REPOSITORIES is empty"
        }
    }

    override fun createDraft(
        repositoryUrl: String,
        workspace: Path,
        branchName: String,
        title: String,
        body: String,
        changedFiles: List<String>
    ): PullRequestReference {
        require(token.isNotBlank()) { "GitHub publishing requires AGENT_GITHUB_TOKEN" }
        val repository = parseRepository(repositoryUrl)
        require(repository in allowedRepositories) {
            "GitHub repository '$repository' is not in agent.github.allowed-repositories"
        }
        require(branchName.matches(BRANCH_NAME)) { "Pull request branch name is invalid" }
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "Pull request title is invalid" }

        val defaultBranch = repositoryDetails(repository).path("default_branch").asText()
            .takeIf(String::isNotBlank)
            ?: throw IllegalStateException("GitHub repository did not provide a default branch")

        val existing = request(
            "GET",
            "/repos/$repository/pulls?state=all&head=${encode("${repository.substringBefore('/')}:$branchName")}&per_page=100"
        ).takeIf { it.isArray }?.firstOrNull { it.path("head").path("ref").asText() == branchName }
        if (existing != null) return existing.toPullRequestReference()

        publishBranch(workspace, repository, branchName, changedFiles)
        val requestBody = objectMapper.createObjectNode().apply {
            put("title", title)
            put("head", branchName)
            put("base", defaultBranch)
            put("body", body.take(MAX_BODY_LENGTH))
            put("draft", true)
        }
        return request(
            "POST",
            "/repos/$repository/pulls",
            objectMapper.writeValueAsString(requestBody)
        ).toPullRequestReference()
    }

    private fun publishBranch(workspace: Path, repository: String, branchName: String, changedFiles: List<String>) {
        val root = workspace.toAbsolutePath().normalize()
        require(changedFiles.isNotEmpty()) { "There are no repository changes to publish" }
        val files = changedFiles.distinct()
        require(files.size <= MAX_CHANGED_FILES && files.sumOf { it.length } <= MAX_CHANGED_PATH_CHARS) {
            "Changed file list exceeds the pull-request publishing limits"
        }
        require(files.none { '\n' in it || '\r' in it || '\u0000' in it }) {
            "Repository contains a changed path that cannot be safely published"
        }
        require(files.all { path ->
            val relative = Path.of(path)
            if (relative.isAbsolute || relative.any { it.toString() == ".." }) {
                false
            } else {
                var current = root
                var safe = true
                relative.forEach { segment ->
                    current = current.resolve(segment)
                    if (Files.isSymbolicLink(current)) safe = false
                }
                safe && current.normalize().startsWith(root)
            }
        }) { "A changed file path escapes the repository workspace" }

        val globalConfig = Files.createTempFile("agent-git-", ".config")
        val hooksPath = Files.createTempDirectory("agent-git-hooks-")
        try {
            val environment = mapOf(
                "GIT_CONFIG_GLOBAL" to globalConfig.toString(),
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_TERMINAL_PROMPT" to "0",
                "GIT_CONFIG_COUNT" to "0"
            )
            val remote = runGit(
                listOf("git", "config", "--local", "--get", "remote.origin.url"),
                root,
                environment
            ).output.trim()
            require(parseRepository(remote) == repository) { "Git workspace origin does not match the authorized repository" }

            val pushUrls = commands.run(
                listOf("git", "config", "--local", "--get-all", "remote.origin.pushurl"),
                root,
                timeout = GIT_TIMEOUT,
                maxOutputChars = MAX_COMMAND_OUTPUT,
                environment = environment
            )
            require(pushUrls.exitCode == 1 && !pushUrls.timedOut) {
                "Custom Git push URLs are not permitted for pull-request publishing"
            }
            val rewrites = commands.run(
                listOf("git", "config", "--local", "--get-regexp", """^url\..*\.insteadof$"""),
                root,
                timeout = GIT_TIMEOUT,
                maxOutputChars = MAX_COMMAND_OUTPUT,
                environment = environment
            )
            require(rewrites.exitCode == 1 && !rewrites.timedOut) {
                "Git URL rewrites are not permitted for pull-request publishing"
            }

            fun git(
                args: List<String>,
                extraEnvironment: Map<String, String> = emptyMap(),
                allowNoDiff: Boolean = false
            ): dev.agentplatform.domain.port.CommandResult =
                runGit(
                    listOf(
                        "git", "-c", "core.hooksPath=$hooksPath",
                        "-c", "core.fsmonitor=false",
                        "-c", "http.https://github.com/.proxy=",
                        "-c", "http.followRedirects=false"
                    ) + args,
                    root,
                    environment + extraEnvironment,
                    allowNoDiff
                )

            val stagedBefore = git(listOf("diff", "--cached", "--quiet"), allowNoDiff = true)
            check(stagedBefore.exitCode == 0) { "Workspace contains staged changes outside the agent patch" }
            git(listOf("checkout", "-b", branchName))
            git(listOf("add", "--all", "--", *files.toTypedArray()))
            val stagedDiff = git(listOf("diff", "--cached", "--quiet"), allowNoDiff = true)
            check(stagedDiff.exitCode == 1) { "No changes were staged for the pull request" }
            git(
                listOf(
                    "-c", "user.name=Coding Agent", "-c", "user.email=agent@localhost.invalid",
                    "-c", "commit.gpgsign=false", "commit", "-m", "Agent: $branchName"
                )
            )

            val authorization = Base64.getEncoder()
                .encodeToString("x-access-token:$token".toByteArray(StandardCharsets.UTF_8))
            val push = git(
                listOf(
                    "-c", "credential.helper=", "-c", "http.proxy=",
                    "push", "--set-upstream", "https://github.com/$repository.git", "HEAD:refs/heads/$branchName"
                ),
                mapOf(
                    "GIT_CONFIG_COUNT" to "1",
                    "GIT_CONFIG_KEY_0" to "http.https://github.com/.extraheader",
                    "GIT_CONFIG_VALUE_0" to "AUTHORIZATION: basic $authorization"
                )
            )
            check(!push.timedOut && push.exitCode == 0) {
                "Could not push the pull-request branch: ${sanitize(push.output)}"
            }
        } finally {
            Files.deleteIfExists(globalConfig)
            Files.deleteIfExists(hooksPath)
        }
    }

    private fun runGit(
        command: List<String>,
        root: Path,
        environment: Map<String, String>,
        allowNoDiff: Boolean = false
    ): dev.agentplatform.domain.port.CommandResult {
        val result = commands.run(
            command,
            root,
            timeout = if (command.contains("push")) PUSH_TIMEOUT else GIT_TIMEOUT,
            maxOutputChars = MAX_COMMAND_OUTPUT,
            environment = environment
        )
        check(!result.timedOut && (result.exitCode == 0 || (allowNoDiff && result.exitCode == 1))) {
            "Git operation failed: ${sanitize(result.output).ifBlank { "git exited with ${result.exitCode}" }}"
        }
        return result
    }

    private fun repositoryDetails(repository: String) =
        request("GET", "/repos/$repository")

    private fun request(method: String, path: String, body: String? = null): com.fasterxml.jackson.databind.JsonNode {
        val endpoint = URI.create(apiUrl.trimEnd('/') + path)
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
        val request = if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        }
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("GitHub request was interrupted", failure)
        } catch (failure: IOException) {
            throw IllegalStateException("Could not reach the configured GitHub API", failure)
        }
        check(response.statusCode() in 200..299) {
            "GitHub API returned HTTP ${response.statusCode()}: ${sanitize(response.body())}"
        }
        return try {
            objectMapper.readTree(response.body())
        } catch (failure: IOException) {
            throw IllegalStateException("GitHub API returned invalid JSON", failure)
        }
    }

    private fun com.fasterxml.jackson.databind.JsonNode.toPullRequestReference(): PullRequestReference {
        val number = path("number").takeIf { it.canConvertToInt() }?.asInt()
            ?: throw IllegalStateException("GitHub pull request response did not contain a number")
        val url = path("html_url").takeIf { it.isTextual }?.asText()
            ?: throw IllegalStateException("GitHub pull request response did not contain a URL")
        val title = path("title").takeIf { it.isTextual }?.asText().orEmpty()
        val state = path("state").takeIf { it.isTextual }?.asText()
            ?: throw IllegalStateException("GitHub pull request response did not contain a state")
        require(state in setOf("open", "closed")) { "GitHub returned an unsupported pull request state" }
        val draft = path("draft").takeIf { it.isBoolean }?.asBoolean()
            ?: throw IllegalStateException("GitHub pull request response did not contain a draft status")
        return PullRequestReference(number, url, title, state, draft)
    }

    private fun parseRepository(url: String): String {
        val uri = try {
            URI.create(url)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Pull requests can only be created for a GitHub repository URL", failure)
        }
        require(uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            REPOSITORY_PATH.matches(uri.path)
        ) { "Pull requests can only be created for a GitHub repository URL" }
        return uri.path.trim('/').removeSuffix(".git").lowercase()
    }

    private fun parseApiUrl(url: String): URI {
        val uri = try {
            URI.create(url)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("GitHub API URL is invalid", failure)
        }
        require(uri.isAbsolute && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "GitHub API URL must be an absolute HTTP or HTTPS URL"
        }
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "GitHub API URL must use HTTP or HTTPS"
        }
        return uri
    }

    private fun isLoopback(host: String) =
        host.equals("localhost", true) || host == "127.0.0.1" || host == "[::1]"

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun sanitize(text: String): String {
        val encoded = Base64.getEncoder()
            .encodeToString("x-access-token:$token".toByteArray(StandardCharsets.UTF_8))
        return text.replace(token, "[redacted]").replace(encoded, "[redacted]").take(MAX_ERROR_LENGTH)
    }

    private companion object {
        val REPOSITORY_PATH = Regex("""^/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\.git)?/?$""")
        val REPOSITORY_NAME = Regex("""^[a-z0-9_.-]+/[a-z0-9_.-]+$""")
        val BRANCH_NAME = Regex("""^agent/task-[0-9a-fA-F-]{36}$""")
        val HTTP_TIMEOUT = Duration.ofSeconds(30)
        val GIT_TIMEOUT = Duration.ofSeconds(30)
        val PUSH_TIMEOUT = Duration.ofMinutes(2)
        const val MAX_TITLE_LENGTH = 256
        const val MAX_BODY_LENGTH = 60_000
        const val MAX_CHANGED_FILES = 250
        const val MAX_CHANGED_PATH_CHARS = 20_000
        const val MAX_COMMAND_OUTPUT = 4_000
        const val MAX_ERROR_LENGTH = 1_000
    }
}

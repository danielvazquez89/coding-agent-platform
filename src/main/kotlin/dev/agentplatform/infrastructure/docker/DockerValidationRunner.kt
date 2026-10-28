package dev.agentplatform.infrastructure.docker

import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.ValidationCheck
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import dev.agentplatform.domain.port.ValidationRunner
import dev.agentplatform.infrastructure.ExecutionControlHttp
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*

@ApplicationScoped
open class DockerValidationRunner @Inject constructor(
    private val commands: CommandExecutor,
    @ConfigProperty(name = "agent.execution.docker.maven-image", defaultValue = "maven:3.9.13-eclipse-temurin-21-noble")
    private val mavenImage: String,
    @ConfigProperty(name = "agent.execution.docker.postgres-image", defaultValue = "postgres:18-alpine")
    private val postgresImage: String,
    @ConfigProperty(name = "agent.execution.docker.maven-repository", defaultValue = "\${user.home}/.m2/repository")
    private val configuredMavenRepository: String,
    @ConfigProperty(name = "agent.execution.validation-timeout-seconds", defaultValue = "600")
    private val validationTimeoutSeconds: Long,
    @ConfigProperty(name = "agent.execution.docker.database-ready-attempts", defaultValue = "40")
    private val databaseReadyAttempts: Int,
    @ConfigProperty(name = "agent.execution.docker.database-ready-delay-millis", defaultValue = "500")
    private val databaseReadyDelayMillis: Long,
    @ConfigProperty(name = "agent.execution.docker.maven-cache-volume", defaultValue = "disabled")
    private val mavenCacheVolume: String = "disabled",
    @ConfigProperty(name = "agent.execution.docker.workspace-volume", defaultValue = "disabled")
    private val workspaceVolume: String = "disabled",
    @ConfigProperty(name = "agent.execution.workspace-root", defaultValue = "")
    private val configuredWorkspaceRoot: String = "",
    @ConfigProperty(name = "agent.execution.control-plane-url", defaultValue = "disabled")
    private val controlPlaneUrl: String = "disabled",
    @ConfigProperty(name = "agent.execution.control-plane-token", defaultValue = "disabled")
    private val controlPlaneToken: String = "disabled",
    @ConfigProperty(name = "agent.execution.control-plane-timeout-seconds", defaultValue = "7200")
    private val controlPlaneTimeoutSeconds: Long = 7200
) : ValidationRunner {
    init {
        require(validationTimeoutSeconds > 0) { "Validation timeout must be positive" }
        require(databaseReadyAttempts > 0) { "Database readiness attempts must be positive" }
        require(databaseReadyDelayMillis >= 0) { "Database readiness delay cannot be negative" }
        require(controlPlaneTimeoutSeconds > 0) { "Control-plane timeout must be positive" }
        require(!isConfigured(controlPlaneUrl) || isConfigured(controlPlaneToken)) {
            "Execution control-plane token is required when a control-plane URL is configured"
        }
        require(isConfigured(workspaceVolume) == isConfigured(mavenCacheVolume)) {
            "Workspace and Maven cache Docker volumes must be configured together"
        }
    }
    open override fun validate(workspace: Path): ValidationCheck {
        if (isConfigured(controlPlaneUrl)) {
            return ExecutionControlHttp.validate(
                controlPlaneUrl, controlPlaneToken, workspace, controlPlaneTimeoutSeconds
            )
        }
        if (!Files.isRegularFile(workspace.resolve("pom.xml"))) {
            return ValidationCheck(
                name = "Maven tests",
                command = "mvn -B -o test in isolated Docker",
                outcome = CheckOutcome.SKIPPED,
                details = "This first worker supports Maven repositories only."
            )
        }
        val repo = Path.of(configuredMavenRepository).toAbsolutePath().normalize()
        if (!Files.isDirectory(repo)) {
            return ValidationCheck(
                name = "Maven tests",
                command = "mvn -B -o test in isolated Docker",
                outcome = CheckOutcome.SKIPPED,
                details = "The host Maven artifact cache is missing at $repo; offline validation cannot start."
            )
        }

        val id = UUID.randomUUID().toString().replace("-", "")
        val network = "agent-$id"
        val database = "agent-db-$id"
        val commandName = "mvn -B -o test in isolated Docker"
        var output = StringBuilder()
        var outcome = CheckOutcome.FAILED
        var exitCode: Int? = null
        var timedOut = false
        val reportCutoff = Instant.now()
        val startedAt = System.nanoTime()
        try {
            // `docker network create` and `docker run -d` print opaque 64-hex IDs on success; they
            // are not validation evidence and would push Maven's verdict down the bounded details.
            val networkResult = commands.run(listOf("docker", "network", "create", "--internal", network), timeout = Duration.ofSeconds(30))
            checkCommand(networkResult, "Could not create private Docker network")

            val databaseResult = commands.run(
                listOf(
                    "docker", "run", "-d", "--name", database, "--network", network, "--network-alias", "agent-db",
                    "--label", "coding-agent-platform.attempt=$id", "--cpus=0.5", "--memory=2g", "--pids-limit=1024",
                    "-e", "POSTGRES_PASSWORD=agent-temp-password",
                    postgresImage
                ),
                timeout = Duration.ofMinutes(3)
            )
            checkCommand(databaseResult, "Could not start temporary PostgreSQL container")
            waitForDatabase(database, output)

            val workspacePath = workspace.toAbsolutePath().normalize().toString()
            val cachePath = repo.toString()
            val downloaded = mutableSetOf<String>()
            var mavenResult = runOfflineTests(id, network, workspacePath, cachePath)
            appendOutput(output, mavenResult.output)
            var dependencyRounds = 0
            while (dependencyRounds < MAX_DEPENDENCY_ROUNDS && isMissingArtifactFailure(mavenResult)) {
                val missing = missingArtifactCoordinates(mavenResult.output)
                    .filter { it !in downloaded }
                    .take(MAX_ARTIFACTS_PER_ROUND)
                if (missing.isEmpty()) break
                val fetchedAll = missing.all { artifact ->
                    val getResult = commands.run(
                        dependencyGetCommand(id, cachePath, artifact),
                        timeout = Duration.ofMinutes(3),
                        maxOutputChars = MAX_VALIDATION_OUTPUT
                    )
                    appendOutput(output, getResult.output)
                    val fetched = !getResult.timedOut && getResult.exitCode == 0
                    if (fetched) downloaded += artifact
                    else appendOutput(output, "Could not download missing artifact $artifact.")
                    fetched
                }
                if (!fetchedAll) break
                dependencyRounds++
                appendOutput(output, "Retrying offline Maven tests after downloading ${missing.size} missing artifact(s).")
                mavenResult = runOfflineTests(id, network, workspacePath, cachePath)
                appendOutput(output, mavenResult.output)
            }
            exitCode = mavenResult.exitCode.takeIf { !mavenResult.timedOut }
            timedOut = mavenResult.timedOut
            outcome = if (!timedOut && exitCode == 0) CheckOutcome.PASSED else CheckOutcome.FAILED
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            appendOutput(output, failure.message ?: failure.javaClass.simpleName)
        } finally {
            runCatching { commands.run(listOf("docker", "rm", "-f", database), timeout = Duration.ofSeconds(20), maxOutputChars = 1000) }
            runCatching { commands.run(listOf("docker", "network", "rm", network), timeout = Duration.ofSeconds(20), maxOutputChars = 1000) }
        }

        // The process that requested validation also holds the workspace on disk: a bind-mounted
        // directory locally, or the same named volume mounted at the configured workspace root in
        // Compose. Surefire reports written by the test container are therefore observable in both
        // modes. An empty result is still evidence that the cited test did not pass.
        val testResults = SurefireReports.read(workspace, reportCutoff)
        val details = if (timedOut) "Validation timed out after ${validationTimeoutSeconds}s.\n$output" else output.toString()
        return ValidationCheck(
            name = "Maven tests",
            command = commandName,
            outcome = outcome,
            exitCode = exitCode,
            details = boundedValidationDetails(details),
            durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
            testResults = testResults,
            testResultsAvailable = true
        )
    }

    private fun runOfflineTests(id: String, network: String, workspacePath: String, cachePath: String) = commands.run(
        offlineMavenCommand(id, network, workspacePath, cachePath),
        timeout = Duration.ofSeconds(validationTimeoutSeconds),
        // Capture more than the report keeps: Maven prints the failing tests and the build verdict at
        // the end, and a capture cut at the display limit would throw the verdict away before the
        // start-and-end trim below can keep it.
        maxOutputChars = MAX_VALIDATION_CAPTURE
    )

    private fun offlineMavenCommand(id: String, network: String, workspacePath: String, cachePath: String): List<String> {
        val workspaceMount = if (!isConfigured(workspaceVolume)) {
            listOf("--mount", "type=bind,source=$workspacePath,target=/workspace", "--workdir", "/workspace")
        } else {
            val root = Path.of(configuredWorkspaceRoot).toAbsolutePath().normalize()
            val path = Path.of(workspacePath).toAbsolutePath().normalize()
            require(path.startsWith(root) && path != root) { "Validation workspace is outside the configured workspace root" }
            val relative = root.relativize(path)
            require(relative.nameCount == 1 && relative.fileName.toString().matches(Regex("""attempt-[A-Za-z0-9-]+"""))) {
                "Validation workspace must be one task-specific directory under the configured workspace root"
            }
            listOf(
                "--mount",
                "type=volume,source=$workspaceVolume,target=/workspace,volume-subpath=${relative.toString().replace('\\', '/')}",
                "--workdir",
                "/workspace"
            )
        }
        val cacheMount = if (!isConfigured(mavenCacheVolume)) {
            listOf("--mount", "type=bind,source=$cachePath,target=/m2/repository,readonly")
        } else {
            listOf("--mount", "type=volume,source=$mavenCacheVolume,target=/m2/repository,readonly")
        }
        return listOf(
            "docker", "run", "--rm", "--name", "agent-test-$id", "--network", network,
            "--label", "coding-agent-platform.attempt=$id", "--cpus=1", "--memory=2g", "--pids-limit=1024",
            "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--tmpfs", "/tmp:rw,exec,nosuid,size=512m", "--user", "185:185",
            *workspaceMount.toTypedArray(), *cacheMount.toTypedArray(),
            "--env", "HOME=/tmp", "--env", "MAVEN_CONFIG=/tmp/maven-config",
            "--env", "MAVEN_OPTS=-Dmaven.repo.local=/m2/repository",
            "--env", "DB_PASSWORD=agent-temp-password", "--env", "QUARKUS_DATASOURCE_USERNAME=postgres",
            "--env", "QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://agent-db:5432/postgres",
            mavenImage, "mvn", "-B", "-o", "test"
        )
    }

    private fun dependencyGetCommand(id: String, cachePath: String, artifact: String): List<String> {
        val suffix = Integer.toHexString(artifact.hashCode())
        val cacheMount = if (!isConfigured(mavenCacheVolume)) {
            listOf("--mount", "type=bind,source=$cachePath,target=/m2/repository")
        } else {
            listOf("--mount", "type=volume,source=$mavenCacheVolume,target=/m2/repository")
        }
        return listOf(
            "docker", "run", "--rm", "--name", "agent-dep-$id-$suffix",
            "--label", "coding-agent-platform.attempt=$id", "--cpus=1", "--memory=2g", "--pids-limit=128",
            "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--tmpfs", "/tmp:rw,exec,nosuid,size=512m",
            *cacheMount.toTypedArray(),
            "--env", "HOME=/tmp", "--env", "MAVEN_OPTS=-Dmaven.repo.local=/m2/repository",
            mavenImage, "mvn", "-B", "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
            "-Dartifact=$artifact"
        )
    }

    private fun isMissingArtifactFailure(result: CommandResult): Boolean {
        if (result.timedOut || result.exitCode == 0) return false
        val text = result.output
        return text.contains("Could not resolve dependencies") ||
            text.contains("has not been downloaded") ||
            text.contains("Could not find artifact") ||
            text.contains("Failed to read artifact descriptor")
    }

    private fun waitForDatabase(database: String, output: StringBuilder) {
        repeat(databaseReadyAttempts) {
            val ready = commands.run(
                listOf("docker", "exec", database, "pg_isready", "-U", "postgres"),
                timeout = Duration.ofSeconds(5),
                maxOutputChars = 1000
            )
            if (!ready.timedOut && ready.exitCode == 0) return
            Thread.sleep(databaseReadyDelayMillis)
        }
        appendOutput(output, "Temporary PostgreSQL container did not become ready.")
        throw IllegalStateException("Temporary PostgreSQL container did not become ready")
    }

    private fun checkCommand(result: CommandResult, message: String) {
        check(!result.timedOut && result.exitCode == 0) { "$message: ${result.output.take(3000)}" }
    }

    private fun appendOutput(target: StringBuilder, value: String) {
        // Every command output is already bounded by its maxOutputChars, so the combined buffer stays
        // small. Do not stop at the display limit here: that would discard later dependency-retry
        // output, which is the run whose verdict matters most.
        target.append(value)
    }

    /**
     * Maven prints each failing test as it runs and the final `Tests run` / `BUILD FAILURE` verdict at
     * the end. A head-only cut can drop the one line the model needs to decide what to fix, so keep the
     * start and the end and say how much is missing. The command capture is deliberately larger than
     * this display limit, so the verdict is available to keep.
     */
    private fun boundedValidationDetails(output: String): String {
        if (output.length <= MAX_VALIDATION_OUTPUT) return output
        val head = MAX_VALIDATION_OUTPUT * 3 / 5
        val tail = MAX_VALIDATION_OUTPUT / 3
        val omitted = output.length - head - tail
        val marker = "\n\n... [$omitted characters omitted; keeping the start and the end] ...\n\n"
        return output.take(head) + marker + output.takeLast(tail)
    }

    internal companion object {
        private const val DISABLED_CONFIGURATION_VALUE = "disabled"

        private fun isConfigured(value: String): Boolean =
            value.isNotBlank() && !value.equals(DISABLED_CONFIGURATION_VALUE, ignoreCase = true)

        const val MAX_VALIDATION_OUTPUT = 12_000
        const val MAX_VALIDATION_CAPTURE = 48_000
        const val MAX_DEPENDENCY_ROUNDS = 3
        const val MAX_ARTIFACTS_PER_ROUND = 8
        private val ARTIFACT_TOKEN = Regex("""[A-Za-z0-9_.-]+""")
        private val PACKAGING = setOf("jar", "pom", "war", "ear", "maven-plugin", "ejb", "bundle")
        private val MISSING_ARTIFACT = Regex(
            """(?:dependency:\s+|artifact\s+|artifact descriptor for\s+)([A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){3,4})"""
        )

        fun missingArtifactCoordinates(output: String): List<String> {
            val found = linkedSetOf<String>()
            for (match in MISSING_ARTIFACT.findAll(output)) {
                toDependencyGetArtifact(match.groupValues[1])?.let(found::add)
            }
            return found.toList()
        }

        private fun toDependencyGetArtifact(raw: String): String? {
            val parts = raw.split(':')
            if (parts.any { !ARTIFACT_TOKEN.matches(it) }) return null
            return when (parts.size) {
                4 -> {
                    val (group, artifact, packaging, version) = parts
                    if (packaging !in PACKAGING) null
                    else if (packaging == "jar") "$group:$artifact:$version"
                    else "$group:$artifact:$version:$packaging"
                }
                5 -> {
                    val (group, artifact, packaging, classifier, version) = parts
                    if (packaging !in PACKAGING) null
                    else "$group:$artifact:$version:$packaging:$classifier"
                }
                else -> null
            }
        }
    }
}

package dev.agentplatform.infrastructure.docker

import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.ValidationCheck
import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import dev.agentplatform.domain.port.ValidationRunner
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

@ApplicationScoped
class DockerValidationRunner(
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
    private val databaseReadyDelayMillis: Long
) : ValidationRunner {
    init {
        require(validationTimeoutSeconds > 0) { "Validation timeout must be positive" }
        require(databaseReadyAttempts > 0) { "Database readiness attempts must be positive" }
        require(databaseReadyDelayMillis >= 0) { "Database readiness delay cannot be negative" }
    }
    override fun validate(workspace: Path): ValidationCheck {
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
        val startedAt = System.nanoTime()
        try {
            val networkResult = commands.run(listOf("docker", "network", "create", "--internal", network), timeout = Duration.ofSeconds(30))
            appendOutput(output, networkResult.output)
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
            appendOutput(output, databaseResult.output)
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

        val details = if (timedOut) "Validation timed out after ${validationTimeoutSeconds}s.\n$output" else output.toString()
        return ValidationCheck(
            name = "Maven tests",
            command = commandName,
            outcome = outcome,
            exitCode = exitCode,
            details = details.take(MAX_VALIDATION_OUTPUT),
            durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        )
    }

    private fun runOfflineTests(id: String, network: String, workspacePath: String, cachePath: String) = commands.run(
        offlineMavenCommand(id, network, workspacePath, cachePath),
        timeout = Duration.ofSeconds(validationTimeoutSeconds),
        maxOutputChars = MAX_VALIDATION_OUTPUT
    )

    private fun offlineMavenCommand(id: String, network: String, workspacePath: String, cachePath: String) = listOf(
        "docker", "run", "--rm", "--name", "agent-test-$id", "--network", network,
        "--label", "coding-agent-platform.attempt=$id", "--cpus=1", "--memory=2g", "--pids-limit=1024",
        "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
        "--tmpfs", "/tmp:rw,exec,nosuid,size=512m", "--user", "1000:1000",
        "--mount", "type=bind,source=$workspacePath,target=/workspace",
        "--mount", "type=bind,source=$cachePath,target=/m2/repository,readonly",
        "--workdir", "/workspace", "--env", "HOME=/tmp", "--env", "MAVEN_CONFIG=/tmp/maven-config",
        "--env", "MAVEN_OPTS=-Dmaven.repo.local=/m2/repository",
        "--env", "DB_PASSWORD=agent-temp-password", "--env", "QUARKUS_DATASOURCE_USERNAME=postgres",
        "--env", "QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://agent-db:5432/postgres",
        mavenImage, "mvn", "-B", "-o", "test"
    )

    private fun dependencyGetCommand(id: String, cachePath: String, artifact: String): List<String> {
        val suffix = Integer.toHexString(artifact.hashCode())
        return listOf(
            "docker", "run", "--rm", "--name", "agent-dep-$id-$suffix",
            "--label", "coding-agent-platform.attempt=$id", "--cpus=1", "--memory=2g", "--pids-limit=128",
            "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
            "--tmpfs", "/tmp:rw,exec,nosuid,size=512m",
            "--mount", "type=bind,source=$cachePath,target=/m2/repository",
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
        if (target.length >= MAX_VALIDATION_OUTPUT) return
        target.append(value.take(MAX_VALIDATION_OUTPUT - target.length))
    }

    internal companion object {
        const val MAX_VALIDATION_OUTPUT = 12_000
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

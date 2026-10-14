package dev.agentplatform.infrastructure.process

import dev.agentplatform.domain.port.CommandExecutor
import dev.agentplatform.domain.port.CommandResult
import jakarta.enterprise.context.ApplicationScoped
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@ApplicationScoped
class LocalCommandExecutor : CommandExecutor {
    override fun run(
        command: List<String>,
        workingDirectory: Path?,
        standardInput: String?,
        timeout: Duration,
        maxOutputChars: Int,
        environment: Map<String, String>
    ): CommandResult {
        require(command.isNotEmpty() && command.none(String::isBlank)) { "Command must contain non-blank arguments" }
        require(!timeout.isNegative && !timeout.isZero) { "Command timeout must be positive" }
        require(maxOutputChars > 0) { "Output limit must be positive" }
        val javaArgumentFile = createJavaArgumentFileIfNeeded(command)
        val processCommand = if (javaArgumentFile == null) command else listOf(command.first(), "@$javaArgumentFile")
        val builder = ProcessBuilder(processCommand).redirectErrorStream(true)
        if (workingDirectory != null) builder.directory(workingDirectory.toFile())
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (exception: IOException) {
            javaArgumentFile?.let { deleteArgumentFile(it, exception) }
            throw IllegalStateException("Could not start command '${command.first()}': ${exception.message}", exception)
        }
        val output = CompletableFuture.supplyAsync {
            readLimited(process.inputStream, maxOutputChars)
        }
        try {
            if (standardInput != null) {
                process.outputStream.use { it.write(standardInput.toByteArray(StandardCharsets.UTF_8)) }
            } else {
                process.outputStream.close()
            }
            val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val text = output.get(5, TimeUnit.SECONDS)
            return CommandResult(if (completed) process.exitValue() else -1, text, timedOut = !completed)
        } catch (exception: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Command execution was interrupted", exception)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            javaArgumentFile?.let { Files.deleteIfExists(it) }
        }
    }

    private fun createJavaArgumentFileIfNeeded(command: List<String>): Path? {
        val executableName = Path.of(command.first()).fileName.toString()
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ||
            (!executableName.equals("java.exe", ignoreCase = true) && !executableName.equals("java", ignoreCase = true)) ||
            command.sumOf { it.length + 1 } <= WINDOWS_COMMAND_LENGTH_LIMIT
        ) {
            return null
        }

        val argumentFile = Files.createTempFile("agent-java-", ".args")
        try {
            val contents = command.drop(1).joinToString(System.lineSeparator()) { it.toJavaArgumentFileEntry() } +
                System.lineSeparator()
            Files.writeString(argumentFile, contents, StandardCharsets.UTF_8)
            return argumentFile
        } catch (failure: IOException) {
            deleteArgumentFile(argumentFile, failure)
            throw IllegalStateException("Could not prepare Java argument file: ${failure.message}", failure)
        }
    }

    private fun String.toJavaArgumentFileEntry(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun deleteArgumentFile(path: Path, failure: Throwable) {
        try {
            Files.deleteIfExists(path)
        } catch (cleanupFailure: IOException) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun readLimited(input: java.io.InputStream, maxChars: Int): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var remaining = maxChars
        var truncated = false
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (remaining > 0) {
                    val copyCount = minOf(count, remaining)
                    bytes.write(buffer, 0, copyCount)
                    remaining -= copyCount
                    if (copyCount < count) truncated = true
                } else if (count > 0) {
                    truncated = true
                }
            }
        }
        val text = bytes.toString(StandardCharsets.UTF_8)
        return if (truncated) text + "\n[output truncated]" else text
    }

    private companion object {
        const val WINDOWS_COMMAND_LENGTH_LIMIT = 30_000
    }
}

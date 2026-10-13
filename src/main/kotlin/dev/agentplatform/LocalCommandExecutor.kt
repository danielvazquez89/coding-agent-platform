package dev.agentplatform

import jakarta.enterprise.context.ApplicationScoped
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
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
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        if (workingDirectory != null) builder.directory(workingDirectory.toFile())
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (exception: IOException) {
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
}

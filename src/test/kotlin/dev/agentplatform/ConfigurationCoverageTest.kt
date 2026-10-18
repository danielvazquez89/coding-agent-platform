package dev.agentplatform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the "every external setting is an env var with a default in application.properties" rule.
 *
 * The failure this catches is silent: a missing key makes `@ConfigProperty(defaultValue = ...)` apply,
 * so the feature looks configured but is inert. That is exactly how the local-repository property was
 * lost when a comment edit joined two lines into one.
 */
class ConfigurationCoverageTest {

    @Test
    fun `every injected configuration key exists in application properties`() {
        val properties = Files.readAllLines(Path.of("src/main/resources/application.properties"))
            .filterNot { it.trimStart().startsWith("#") }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() }
            .filter(String::isNotEmpty)
            .toSet()

        val injected = Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .toList()
                .flatMap { file -> CONFIG_PROPERTY.findAll(Files.readString(file)).map { it.groupValues[1] }.toList() }
                .toSet()
        }

        assertTrue(injected.size > 20, "Expected to find the injected configuration keys, found ${injected.size}")
        assertEquals(
            emptySet<String>(),
            injected - properties,
            "Configuration keys injected with @ConfigProperty but absent from application.properties"
        )
    }

    private companion object {
        val CONFIG_PROPERTY = Regex("""@ConfigProperty\(\s*name\s*=\s*"([^"]+)"""")
    }
}

package dev.agentplatform.infrastructure.docker

import dev.agentplatform.domain.model.CheckOutcome
import dev.agentplatform.domain.model.TestResult
import org.jboss.logging.Logger
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/**
 * Reads the JUnit XML that Maven Surefire writes into `target/surefire-reports`. The report is
 * machine evidence: a cited test that is absent here did not run, even if its source declaration is
 * present in the workspace.
 *
 * The workspace is untrusted repository content, so the parser disables DTDs and external entities,
 * skips oversized or stale files, and bounds the number of files and results it returns. A malformed
 * report is ignored rather than failing validation; an empty result is an honest "no evidence".
 */
object SurefireReports {
    private val log = Logger.getLogger(SurefireReports::class.java)

    const val MAX_REPORT_FILES = 200
    const val MAX_REPORT_BYTES = 2_000_000L
    const val MAX_RESULTS = 2_000
    private const val STALE_GRACE_SECONDS = 2L

    fun read(workspace: Path, notBefore: Instant? = null): List<TestResult> {
        val directory = workspace.resolve("target").resolve("surefire-reports")
        if (!Files.isDirectory(directory)) return emptyList()

        val files = try {
            Files.list(directory).use { stream ->
                stream
                    .filter { path ->
                        val name = path.fileName.toString()
                        name.startsWith("TEST-") && name.endsWith(".xml")
                    }
                    .filter { Files.isRegularFile(it) }
                    .sorted()
                    .limit(MAX_REPORT_FILES.toLong())
                    .toList()
            }
        } catch (failure: Exception) {
            log.warnf(failure, "Could not list Surefire reports under %s", directory)
            return emptyList()
        }

        return files
            .flatMap { file -> parse(file, notBefore) }
            .sortedWith(compareBy(TestResult::className, TestResult::methodName, TestResult::outcome))
            .take(MAX_RESULTS)
    }

    private fun parse(file: Path, notBefore: Instant?): List<TestResult> {
        return try {
            if (Files.size(file) > MAX_REPORT_BYTES) {
                log.debugf("Skipping oversized Surefire report %s", file)
                return emptyList()
            }
            if (notBefore != null &&
                Files.getLastModifiedTime(file).toInstant().isBefore(notBefore.minusSeconds(STALE_GRACE_SECONDS))
            ) {
                log.debugf("Skipping stale Surefire report %s", file)
                return emptyList()
            }

            val builder = secureFactory().newDocumentBuilder().apply {
                // The default handler prints to stderr before the exception is caught; the workspace
                // is untrusted, so a malformed report must not be able to spam the process output.
                setErrorHandler(object : ErrorHandler {
                    override fun warning(exception: SAXParseException) = throw exception
                    override fun error(exception: SAXParseException) = throw exception
                    override fun fatalError(exception: SAXParseException) = throw exception
                })
            }
            val document = builder.parse(file.toFile())
            val cases = document.getElementsByTagName("testcase")
            val results = mutableListOf<TestResult>()
            for (index in 0 until cases.length) {
                val element = cases.item(index) as? Element ?: continue
                val className = element.getAttribute("classname").trim()
                val methodName = element.getAttribute("name").trim()
                if (className.isEmpty() || methodName.isEmpty()) continue
                results += TestResult(
                    className = className,
                    methodName = methodName,
                    outcome = outcomeOf(element),
                    durationMillis = element.getAttribute("time").toDoubleOrNull()
                        ?.let { (it * 1000).roundToLong() }
                )
            }
            results
        } catch (failure: Exception) {
            log.warnf(
                "Could not parse Surefire report %s: %s",
                file.fileName,
                failure.message ?: failure.javaClass.simpleName
            )
            emptyList()
        }
    }

    private fun secureFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        return factory
    }

    private fun outcomeOf(testCase: Element): CheckOutcome {
        var skipped = false
        for (index in 0 until testCase.childNodes.length) {
            val child = testCase.childNodes.item(index) as? Element ?: continue
            when (child.tagName) {
                "failure", "error" -> return CheckOutcome.FAILED
                "skipped" -> skipped = true
            }
        }
        return if (skipped) CheckOutcome.SKIPPED else CheckOutcome.PASSED
    }
}

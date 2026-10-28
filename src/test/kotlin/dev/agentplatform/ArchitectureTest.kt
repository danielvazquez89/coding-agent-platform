package dev.agentplatform

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the dependency directions the architecture claims. These are deliberately narrower than the
 * golden-rule prose: two couplings are known, documented debt rather than hidden drift.
 */
class ArchitectureTest {
    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.agentplatform")

    @Test
    fun `imports the production classes the rules describe`() {
        assertTrue(classes.any { it.name == "dev.agentplatform.application.worker.TaskExecutionWorker" })
        assertTrue(classes.any { it.name == "dev.agentplatform.infrastructure.tool.WorkspaceToolExecutor" })
        assertTrue(classes.any { it.name == "dev.agentplatform.domain.port.WorkspaceTools" })
    }

    @Test
    fun `domain does not depend on application adapters or infrastructure`() {
        noClasses()
            .that().resideInAPackage("dev.agentplatform.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "dev.agentplatform.api..",
                "dev.agentplatform.application..",
                "dev.agentplatform.infrastructure.."
            )
            .check(classes)
    }

    @Test
    fun `domain uses only the JDK persistence annotations and documented provider JSON types`() {
        noClasses()
            .that().resideInAPackage("dev.agentplatform.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                "java..",
                "kotlin..",
                "org.jetbrains.annotations..",
                "jakarta.persistence..",
                "dev.agentplatform.domain..",
                "com.fasterxml.jackson.databind.."
            )
            .because(
                "domain entities carry JPA mapping annotations and the provider SPI uses Jackson JsonNode; " +
                    "any other framework dependency is a new architectural exception"
            )
            .check(classes)
    }

    @Test
    fun `worker reaches outbound adapters only through domain ports`() {
        noClasses()
            .that().resideInAPackage("dev.agentplatform.application.worker..")
            .should().dependOnClassesThat().resideInAPackage("dev.agentplatform.infrastructure..")
            .check(classes)
    }

    @Test
    fun `api does not depend on outbound adapters`() {
        noClasses()
            .that().resideInAPackage("dev.agentplatform.api..")
            .should().dependOnClassesThat().resideInAPackage("dev.agentplatform.infrastructure..")
            .check(classes)
    }

    @Test
    fun `application reaches infrastructure only through the persistence exception`() {
        noClasses()
            .that().resideInAPackage("dev.agentplatform.application..")
            .should().dependOnClassesThat(
                resideInAPackage("dev.agentplatform.infrastructure..")
                    .and(DescribedPredicate.not(resideInAPackage("dev.agentplatform.infrastructure.persistence..")))
            )
            .because(
                "the Panache repositories are a known pragmatic exception; every other adapter must be " +
                    "reached through a domain port"
            )
            .check(classes)
    }

    @Test
    fun `application uses inbound adapter DTOs only from the api dto package`() {
        noClasses()
            .that().resideInAPackage("dev.agentplatform.application..")
            .should().dependOnClassesThat(
                resideInAPackage("dev.agentplatform.api..")
                    .and(DescribedPredicate.not(resideInAPackage("dev.agentplatform.api.dto..")))
            )
            .because("request and response DTOs are a known pragmatic exception")
            .check(classes)
    }

    @Test
    fun `infrastructure does not depend on inbound or application layers`() {
        noClasses()
            .that().resideInAPackage("dev.agentplatform.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "dev.agentplatform.api..",
                "dev.agentplatform.application.."
            )
            .check(classes)
    }
}

package io.github.silbaram.prism.api

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import io.github.silbaram.prism.sdk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.api.keys=prism-test-api-key-0123456789abcdef",
    "spring.datasource.url=jdbc:h2:mem:scale;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.connection-timeout=500",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "prism.config.cache-ttl=PT0.000000001S"
])
class ScaleIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var layers: ExperimentLayerRepository
    @Autowired lateinit var policies: PopulationPolicyRepository
    @Autowired lateinit var exposures: PopulationExposureRepository
    @Autowired lateinit var outcomes: PopulationConversionRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var receipts: EventReceiptRepository
    @Autowired lateinit var changes: ExperimentChangeRepository
    @Autowired lateinit var sticky: StickyAssignmentRepository
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var environment: Environment
    @TempDir lateinit var directory: Path
    private val url get() = "http://localhost:${environment.getProperty("local.server.port")}"
    private fun options() = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef",
        configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1))
    private fun experiment(key: String) = experiments.save(ExperimentEntity(key = key, description = "", goalEventName = "purchase",
        status = ExperimentStatus.ACTIVE, configurationLocked = true).apply { addVariant(VariantEntity(name = "A", weight = 100)) })
    private fun mutate(key: String, change: (ExperimentEntity) -> Unit) = TransactionTemplate(manager).executeWithoutResult {
        change(experiments.findByKey(key)!!)
        changes.save(ExperimentChangeEntity(experimentId = 1, experimentKey = key, action = "TEST", beforeSnapshot = null, afterSnapshot = "{}"))
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(12).toNanos()
        while (!condition() && System.nanoTime() < until) Thread.sleep(25)
        assertTrue(condition())
    }
    @BeforeEach fun reset() {
        outcomes.deleteAll(); exposures.deleteAll(); conversions.deleteAll(); impressions.deleteAll(); receipts.deleteAll()
        sticky.deleteAll(); experiments.deleteAll(); layers.deleteAll(); changes.deleteAll()
        policies.save(PopulationPolicyEntity())
    }

    @Test fun `local and remote layer membership agree while holdout outcomes remain separate from experiment metrics`() {
        policies.save(PopulationPolicyEntity(holdoutKey = "global", holdoutBasisPoints = 500))
        layers.save(ExperimentLayerEntity("checkout"))
        experiment("first"); experiment("second")
        mutate("first") { it.layerKey = "checkout"; it.layerStart = 0; it.layerEnd = 5000 }
        mutate("second") { it.layerKey = "checkout"; it.layerStart = 5000; it.layerEnd = 10000 }
        val policy = io.github.silbaram.prism.core.model.HoldoutPolicy("global", 500)
        val holdout = (0..1000).map { "u-$it" }.first { policy.excludes(it) }
        val eligible = (0..1000).map { "u-$it" }.first { !policy.excludes(it) }
        PrismClient(url, options = options()).use { local ->
            assertTrue(local.refreshConfig())
            assertTrue(local.isInHoldout(holdout)!!)
            assertFalse(local.isInHoldout(eligible)!!)
            assertFalse(local.trackPopulationConversion(holdout, "purchase"))
            for (user in listOf(holdout, eligible)) {
                assertTrue(local.recordPopulationExposure(user))
                assertTrue(local.recordPopulationExposure(user))
                assertTrue(local.trackPopulationConversion(user, "purchase"))
            }
            PrismClient(url, options = options().copy(evaluationMode = EvaluationMode.REMOTE)).use { remote ->
                for (user in listOf(holdout, eligible)) {
                    val localAssignments = listOf("first", "second").map { local.evaluate(user, it).variant }
                    val remoteAssignments = listOf("first", "second").map { remote.assign(user, it).variant }
                    assertEquals(localAssignments, remoteAssignments)
                    assertEquals(if (user == holdout) 0 else 1, localAssignments.count { it != null })
                }
            }
            assertTrue(local.flush())
        }
        assertEquals(2, exposures.count())
        assertEquals(2, outcomes.count())
        assertEquals(1, impressions.count())
        assertEquals(0, conversions.count())
        assertEquals(setOf("HOLDOUT", "ELIGIBLE"), outcomes.outcomes("global").map { it[1] }.toSet())
    }

    @Test fun `sticky assignments survive settings changes and client restart but never bypass holdout`() {
        experiment("sticky")
        mutate("sticky") { it.stickyBucketing = true; it.addVariant(VariantEntity(name = "B", weight = 0)) }
        fun durable() = options().copy(stickyAssignmentStore = FileStickyAssignmentStore(directory))
        PrismClient(url, options = durable()).use { assertEquals("A", it.evaluate("u", "sticky").variant) }
        PrismClient(url, options = options().copy(evaluationMode = EvaluationMode.REMOTE)).use { remote ->
            assertEquals("A", remote.assign("u", "sticky").variant)
            mutate("sticky") { it.variants[0].weight = 0; it.variants[1].weight = 100 }
            assertEquals("A", remote.assign("u", "sticky").variant)
        }
        PrismClient(url, options = durable()).use { local ->
            assertEquals("A", local.evaluate("u", "sticky").variant)
            policies.save(PopulationPolicyEntity(holdoutBasisPoints = 10000))
            assertTrue(local.refreshConfig())
            assertNull(local.evaluate("u", "sticky").variant)
            assertTrue(local.isInHoldout("u")!!)
        }
    }

    @Test fun `SSE propagates pause and resume without waiting for hourly SDK polling`() {
        experiment("live")
        PrismClient(url, options = options().copy(configStreaming = true)).use { local ->
            assertEquals("A", local.evaluate("u", "live").variant)
            mutate("live") { it.status = ExperimentStatus.PAUSED }
            await { local.evaluate("u", "live").variant == null }
            mutate("live") { it.status = ExperimentStatus.ACTIVE }
            await { local.evaluate("u", "live").variant == "A" }
            assertEquals(0, impressions.count())
        }
    }
}

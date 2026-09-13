package io.github.silbaram.prism.api

import com.fasterxml.jackson.module.kotlin.*
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.TimeUnit
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.*
import java.time.*
import java.util.UUID

@Tag("kafka")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.api.keys=prism-test-api-key-0123456789abcdef", "prism.pipeline.mode=KAFKA",
    "prism.pipeline.max-outbox-rows=1",
    "spring.datasource.url=jdbc:h2:mem:kafka-pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"
])
class KafkaPipelineIntegrationTest {
    @Autowired lateinit var environment: Environment
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var inbox: PipelineInboxRepository
    @Autowired lateinit var analysisPlans: AnalysisPlanRepository
    @Autowired lateinit var analysisObservations: AnalysisObservationRepository
    @Autowired lateinit var policies: PopulationPolicyRepository
    @Autowired lateinit var populationExposures: PopulationExposureRepository
    @Autowired lateinit var populationConversions: PopulationConversionRepository
    private val mapper = jacksonObjectMapper()
    companion object {
        private val prefix = "prism-test-${UUID.randomUUID()}"
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("prism.pipeline.bootstrap-servers") { System.getenv("PRISM_TEST_KAFKA_BOOTSTRAP") }
            registry.add("prism.pipeline.topic") { "$prefix-events" }
            registry.add("prism.pipeline.warehouse-topic") { "$prefix-warehouse" }
            registry.add("prism.pipeline.dead-letter-topic") { "$prefix-dead-letter" }
            registry.add("prism.pipeline.group-id") { "$prefix-workers" }
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(25).toNanos()
        while (!condition() && System.nanoTime() < until) Thread.sleep(50)
        assertTrue(condition())
    }
    @Test fun `broker acknowledgement precedes eventual materialization and exports both experiment and population events`() {
        experiments.save(ExperimentEntity(key = "checkout", description = "", status = ExperimentStatus.ACTIVE).apply {
            addVariant(VariantEntity(name = "A", weight = 100))
        })
        policies.save(PopulationPolicyEntity(holdoutBasisPoints = 0))
        analysisPlans.save(AnalysisPlanEntity(experiments.findByKey("checkout")!!.id!!, "A", 1, 1,
            segmentsJson = "{\"device\":[\"mobile\"]}", cupedEnabled = true,
            baselineCutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(2), createdAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(1)))
        val exposure = ClientEvent(UUID.randomUUID().toString(), "exposure", "u", "checkout", "A", Instant.now().toString(), "b".repeat(64),
            analysis = ExposureAnalysisContext(mapOf("device" to "mobile"), 7.0, Instant.now().minusSeconds(10800).toString()))
        val conversion = exposure.copy(eventId = UUID.randomUUID().toString(), type = "conversion", eventName = "purchase", exposureEventId = exposure.eventId, analysis = null)
        val cohort = exposure.copy(eventId = UUID.randomUUID().toString(), type = "population_exposure", experimentKey = "global-v1", variant = "ELIGIBLE", analysis = null)
        val cohortConversion = cohort.copy(eventId = UUID.randomUUID().toString(), type = "population_conversion", eventName = "purchase", exposureEventId = cohort.eventId)
        HttpClient.newHttpClient().use { http ->
            fun send(events: List<ClientEvent>): EventsResponse {
                val request = HttpRequest.newBuilder(URI.create("http://localhost:${environment.getProperty("local.server.port")}/v1/events"))
                    .header("X-Prism-Api-Key", "prism-test-api-key-0123456789abcdef").header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(12)).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(EventsRequest(events)))).build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode(), response.body())
                return mapper.readValue(response.body())
            }
            // The referenced exposures deliberately arrive in later broker records.
            val events = listOf(conversion, cohortConversion, exposure, cohort)
            assertTrue(send(listOf(conversion, cohortConversion)).results.all { it.status == EventStatus.QUEUED })
            await { inbox.count() == 2L && inbox.findAll().all { it.attempts > 0 } }
            assertTrue(send(listOf(exposure, cohort)).results.all { it.status == EventStatus.QUEUED })
            await { conversions.count() == 1L && populationConversions.count() == 1L }
            assertTrue(send(events).results.all { it.status == EventStatus.QUEUED })
            await { inbox.count() == 4L && inbox.findAll().all { it.status == "DONE" } }
            assertEquals(1, impressions.count())
            assertEquals(1, populationExposures.count())
            assertEquals(exposure, mapper.readValue<ClientEvent>(inbox.findAll().single { it.eventId == exposure.eventId }.payload))
            assertEquals(7.0, analysisObservations.findAll().single().baselineValue,
                "cutoff=${analysisPlans.findAll().single().baselineCutoff}, measured=${exposure.analysis!!.baselineMeasuredAt}, exposed=${analysisObservations.findAll().single().exposedAt}")
            assertEquals("{\"device\":\"mobile\"}", analysisObservations.findAll().single().segmentsJson)
            KafkaConsumer<String, String>(mapOf("bootstrap.servers" to System.getenv("PRISM_TEST_KAFKA_BOOTSTRAP"),
                "group.id" to "$prefix-verifier", "auto.offset.reset" to "earliest", "enable.auto.commit" to "false",
                "key.deserializer" to StringDeserializer::class.java.name, "value.deserializer" to StringDeserializer::class.java.name)).use { consumer ->
                consumer.subscribe(listOf("$prefix-warehouse"))
                val exported = mutableMapOf<String, ClientEvent>()
                val until = System.nanoTime() + Duration.ofSeconds(25).toNanos()
                while (exported.size < 4 && System.nanoTime() < until) consumer.poll(Duration.ofMillis(250)).forEach {
                    val event = mapper.readValue<ClientEvent>(it.value())
                    assertEquals(event.eventId, it.key())
                    exported[event.eventId] = event
                }
                assertEquals(events.associateBy { it.eventId }, exported)
            }
            // Tombstones cannot be domain events, but must not block subsequent records in their partition.
            val afterTombstone = exposure.copy(eventId = UUID.randomUUID().toString(), userId = "after-tombstone")
            KafkaProducer<String, String>(mapOf("bootstrap.servers" to System.getenv("PRISM_TEST_KAFKA_BOOTSTRAP"),
                "key.serializer" to StringSerializer::class.java.name, "value.serializer" to StringSerializer::class.java.name)).use { producer ->
                producer.send(ProducerRecord<String, String>("$prefix-events", 0, "tombstone", null)).get(5, TimeUnit.SECONDS)
                producer.send(ProducerRecord("$prefix-events", 0, "next", mapper.writeValueAsString(afterTombstone))).get(5, TimeUnit.SECONDS)
            }
            await { impressions.findByEventId(afterTombstone.eventId) != null }
            // Optional JSON members must work with HTTP Jackson 3 as well as Kafka Jackson 2.
            val baselineOnly = exposure.copy(eventId = UUID.randomUUID().toString(), userId = "baseline-only",
                analysis = exposure.analysis!!.copy(segments = emptyMap(), baselineValue = 0.0))
            val json = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(EventsRequest(listOf(baselineOnly)))
            (json["events"][0]["analysis"] as com.fasterxml.jackson.databind.node.ObjectNode).remove("segments")
            val baselineResponse = http.send(HttpRequest.newBuilder(URI.create("http://localhost:${environment.getProperty("local.server.port")}/v1/events"))
                .header("X-Prism-Api-Key", "prism-test-api-key-0123456789abcdef").header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12)).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(json))).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, baselineResponse.statusCode(), baselineResponse.body())
            assertEquals(EventStatus.QUEUED, mapper.readValue<EventsResponse>(baselineResponse.body()).results.single().status)
            await { analysisObservations.findAll().any { it.userId == "baseline-only" } }
            assertEquals(0.0, analysisObservations.findAll().single { it.userId == "baseline-only" }.baselineValue)
        }
    }
}

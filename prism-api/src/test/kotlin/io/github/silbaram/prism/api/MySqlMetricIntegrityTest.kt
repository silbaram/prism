package io.github.silbaram.prism.api

import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionCommand
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionResult
import io.github.silbaram.prism.api.conversion.application.service.TrackConversionService
import io.github.silbaram.prism.api.conversion.application.port.out.LoadImpressionPort
import io.github.silbaram.prism.api.event.EventIngestionService
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.Instant
import java.util.UUID
import java.util.TimeZone
import java.util.concurrent.*

/** Run with :prism-api:mysqlTest. Creates and drops only its own uniquely named databases. */
@Tag("mysql")
class MySqlMetricIntegrityTest {
    @ParameterizedTest(name = "migrate existing database = {0}, JVM zone = {1}")
    @CsvSource("false,UTC", "false,Asia/Seoul", "true,UTC", "true,Asia/Seoul")
    fun `fresh and migrated MySQL schemas preserve exact identities and exposure attribution`(migrate: Boolean, zone: String) {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try { verifySchema(migrate) } finally { TimeZone.setDefault(originalZone) }
    }

    private fun verifySchema(migrate: Boolean) {
        val baseUrl = requireNotNull(System.getenv("PRISM_TEST_MYSQL_URL"))
        val username = System.getenv("PRISM_TEST_MYSQL_USERNAME") ?: "root"
        val password = System.getenv("PRISM_TEST_MYSQL_PASSWORD") ?: ""
        val database = "prism_test_${UUID.randomUUID().toString().replace("-", "")}"
        val url = baseUrl.substringBefore('?').trimEnd('/') + "/$database" +
            "?" + baseUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "$it&" } +
            "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true"
        DriverManager.getConnection(baseUrl, username, password).use { admin ->
            // Deliberately retain the problematic old default: columns must override it.
            admin.createStatement().use {
                it.execute("CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            }
            try {
                DriverManager.getConnection(url, username, password).use { connection ->
                    if (migrate) {
                        ScriptUtils.executeSqlScript(connection, ClassPathResource("mysql/pre-027-schema.sql"))
                        seedHistoricalEvents(connection)
                        ScriptUtils.executeSqlScript(connection, ClassPathResource("migrations/027_metric_integrity.sql"))
                        ScriptUtils.executeSqlScript(connection, ClassPathResource("migrations/028_exact_identity_and_attribution.sql"))
                        ScriptUtils.executeSqlScript(connection, ClassPathResource("migrations/029_local_evaluation_events.sql"))
                        assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM log_conversion_unattributed_archive"))
                        assertEquals(3L, scalar(connection, "SELECT COUNT(*) FROM log_conversion WHERE impression_id IS NULL"))
                    } else {
                        ScriptUtils.executeSqlScript(connection, ClassPathResource("schema.sql"))
                    }
                    assertExactCollations(connection)
                    assertEquals(2L, scalar(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('log_impression', 'log_conversion') " +
                        "AND COLUMN_NAME = 'timestamp' AND DATETIME_PRECISION = 6"))
                }

                SpringApplicationBuilder(PrismApiApplication::class.java).web(WebApplicationType.NONE).run(
                    "--spring.datasource.url=$url", "--spring.datasource.username=$username",
                    "--spring.datasource.password=$password", "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                    "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never",
                    "--spring.jpa.properties.hibernate.show_sql=false", "--spring.main.banner-mode=off"
                ).use { context ->
                    val experiments = context.getBean(ExperimentRepository::class.java)
                    val impressions = context.getBean(ImpressionLogRepository::class.java)
                    val conversions = context.getBean(ConversionLogRepository::class.java)
                    val lookup = context.getBean(LoadImpressionPort::class.java)
                    val tracker = context.getBean(TrackConversionService::class.java)
                    fun track(user: String, key: String = "checkout", event: String = "purchase") =
                        tracker.trackConversion(TrackConversionCommand(userId = user, experimentKey = key, eventName = event))

                    if (migrate) {
                        assertNull(experiments.findByKey("legacy")!!.goalEventName)
                        assertEquals(1L, conversions.countConversionsByVariant("legacy", "purchase").single()[1])
                        assertEquals(1L, conversions.countEventsByVariant("legacy").single()[3])
                    }
                    val keys = listOf("checkout", "Checkout", "chéckout", "checkout ")
                    keys.forEach { key ->
                        experiments.save(ExperimentEntity(key = key, description = "exact identity", goalEventName = "purchase").apply {
                            addVariant(VariantEntity(name = "A", weight = 100))
                        })
                    }
                    assertEquals(keys.size, keys.map { experiments.findByKey(it)!!.id }.toSet().size)
                    val ahead = LocalDateTime.now().plusHours(1)
                    impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "User-1", timestamp = ahead))
                    val otherUsers = listOf("user-1", "User-1 ", "Usér-1")
                    otherUsers.forEach { user ->
                        assertNull(lookup.loadLatestImpression(user, "checkout"))
                        assertInstanceOf(TrackConversionResult.Rejected::class.java, track(user))
                    }
                    keys.drop(1).forEach { key ->
                        assertNull(lookup.loadLatestImpression("User-1", key))
                        assertInstanceOf(TrackConversionResult.Rejected::class.java, track("User-1", key))
                    }
                    assertEquals(if (migrate) 3L else 0L, conversions.count())
                    impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "user-1", timestamp = ahead))
                    assertInstanceOf(TrackConversionResult.Recorded::class.java, track("User-1"))
                    assertEquals(2L, impressions.countImpressionsByVariant("checkout").single()[1])
                    assertEquals(1L, conversions.countConversionsByVariant("checkout", "purchase").single()[1])
                    otherUsers.drop(1).forEach { user ->
                        impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = user, timestamp = ahead))
                        assertInstanceOf(TrackConversionResult.Recorded::class.java, track(user))
                    }
                    assertEquals(4L, impressions.countImpressionsByVariant("checkout").single()[1])
                    assertEquals(3L, conversions.countConversionsByVariant("checkout", "purchase").single()[1])
                    listOf("Purchase", "púrchase", "purchase ").forEach { event ->
                        assertInstanceOf(TrackConversionResult.Recorded::class.java, track("user-1", event = event))
                    }
                    assertEquals(3L, conversions.countConversionsByVariant("checkout", "purchase").single()[1])
                    assertEquals(4, conversions.countEventsByVariant("checkout").size)

                    listOf("a", "A ").forEach { variant ->
                        val latest = impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = variant,
                            userId = "User-1", timestamp = ahead.minusMinutes(1)))
                        assertEquals(latest.id, lookup.loadLatestImpression("User-1", "checkout")!!.id)
                        assertInstanceOf(TrackConversionResult.Recorded::class.java, track("User-1"))
                        val stored = conversions.findAll().filter { it.impressionId == latest.id }.single()
                        assertEquals(variant, stored.variant)
                        assertTrue(stored.timestamp < latest.timestamp)
                    }
                    assertEquals(setOf("A", "a", "A "), impressions.countImpressionsByVariant("checkout").map { it[0] }.toSet())
                    val goals = conversions.countConversionsByVariant("checkout", "purchase").associate { it[0] to it[1] }
                    assertEquals(mapOf("A" to 3L, "a" to 1L, "A " to 1L), goals)
                    verifyEventIngestion(context.getBean(EventIngestionService::class.java), impressions, conversions)
                    verifyOccurrenceOrder(context.getBean(EventIngestionService::class.java), lookup, impressions,
                        context.getBean(javax.sql.DataSource::class.java))
                }
            } finally {
                admin.createStatement().use { it.execute("DROP DATABASE $database") }
            }
        }
    }

    private fun verifyOccurrenceOrder(service: EventIngestionService, lookup: LoadImpressionPort,
        impressions: ImpressionLogRepository, dataSource: javax.sql.DataSource) {
        val older = ClientEvent(UUID.randomUUID().toString(), "exposure", "delayed", "checkout", "A",
            "2026-09-13T00:00:00.100123Z", "a".repeat(64))
        val newer = older.copy(eventId = UUID.randomUUID().toString(), variant = "B", timestamp = "2026-09-13T00:00:00.900123Z")
        assertTrue(service.ingest(listOf(newer, older)).results.all { it.status == EventStatus.ACCEPTED })
        assertEquals(900123000, impressions.findByEventId(newer.eventId)!!.timestamp.nano)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT UNIX_TIMESTAMP(timestamp) * 1000000 FROM log_impression WHERE event_id = ?").use { query ->
                query.setString(1, newer.eventId)
                query.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val instant = Instant.parse(newer.timestamp)
                    assertEquals(instant.epochSecond * 1_000_000 + instant.nano / 1000, rows.getLong(1),
                        "Stored instant must preserve the SDK's UTC timestamp")
                }
            }
        }
        assertEquals(newer.eventId, lookup.loadLatestOccurredImpression("delayed", "checkout")!!.eventId)
        assertEquals(older.eventId, lookup.loadLatestImpression("delayed", "checkout")!!.eventId)
        val tieHigh = newer.copy(eventId = "20000000-0000-0000-0000-000000000001", userId = "tie")
        val tieLow = tieHigh.copy(eventId = "10000000-0000-0000-0000-000000000001", variant = "A")
        assertTrue(service.ingest(listOf(tieHigh, tieLow)).results.all { it.status == EventStatus.ACCEPTED })
        assertEquals(tieHigh.eventId, lookup.loadLatestOccurredImpression("tie", "checkout")!!.eventId)
    }

    private fun verifyEventIngestion(service: EventIngestionService, impressions: ImpressionLogRepository,
        conversions: ConversionLogRepository) {
        val exposure = ClientEvent(UUID.randomUUID().toString(), "exposure", "batch-user", "checkout", "A",
            Instant.now().toString(), "a".repeat(64))
        val conversion = ClientEvent(UUID.randomUUID().toString(), "conversion", exposure.userId, "checkout", "A",
            Instant.now().minusSeconds(60).toString(), exposure.configVersion, "purchase", exposure.eventId)
        assertEquals(EventStatus.RETRY, service.ingest(listOf(conversion)).results.single().status)
        assertEquals(listOf(EventStatus.ACCEPTED, EventStatus.ACCEPTED),
            service.ingest(listOf(conversion, exposure)).results.map { it.status })
        assertEquals(listOf(EventStatus.DUPLICATE, EventStatus.DUPLICATE),
            service.ingest(listOf(conversion, exposure)).results.map { it.status })
        val recorded = conversions.findAll().single { it.eventId == conversion.eventId }
        assertEquals(impressions.findByEventId(exposure.eventId)!!.id, recorded.impressionId)
        assertEquals(EventStatus.REJECTED, service.ingest(listOf(exposure.copy(userId = "Batch-user"))).results.single().status)
        assertEquals(EventStatus.REJECTED, service.ingest(listOf(conversion.copy(eventId = exposure.eventId,
            exposureEventId = UUID.randomUUID().toString()))).results.single().status)

        val concurrent = exposure.copy(eventId = UUID.randomUUID().toString(), userId = "concurrent")
        val gate = CountDownLatch(4)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit<EventStatus> {
                gate.countDown(); check(gate.await(5, TimeUnit.SECONDS))
                service.ingest(listOf(concurrent)).results.single().status
            } }
            val statuses = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == EventStatus.ACCEPTED })
            assertTrue(statuses.all { it in setOf(EventStatus.ACCEPTED, EventStatus.DUPLICATE, EventStatus.RETRY) })
            assertEquals(EventStatus.DUPLICATE, service.ingest(listOf(concurrent)).results.single().status)
            assertEquals(1, impressions.findAll().count { it.eventId == concurrent.eventId })
        } finally { pool.shutdownNow() }
    }

    private fun seedHistoricalEvents(connection: Connection) {
        connection.createStatement().use { sql ->
            sql.executeUpdate("INSERT INTO experiments (experiment_key, description, status) VALUES ('legacy', '', 'ACTIVE')")
            sql.executeUpdate("INSERT INTO log_impression (experiment_key, variant, user_id, timestamp) VALUES ('legacy', 'A', 'u', '2020-01-01 12:00:00')")
            sql.executeUpdate("""
                INSERT INTO log_conversion (experiment_key, variant, user_id, event_name, timestamp) VALUES
                ('legacy', 'A', 'u', 'purchase', '2020-01-01 12:01:00'),
                ('legacy', 'A', 'u', 'purchase', '2020-01-01 11:59:00'),
                ('legacy', 'A', 'ghost', 'purchase', '2020-01-01 12:01:00'),
                ('legacy', NULL, 'unknown', 'purchase', '2020-01-01 12:01:00')
            """.trimIndent())
        }
    }

    private fun assertExactCollations(connection: Connection) {
        val expected = mapOf("experiments" to setOf("experiment_key", "goal_event_name"),
            "variants" to setOf("name"), "log_impression" to setOf("experiment_key", "variant", "user_id", "event_id"),
            "log_conversion" to setOf("experiment_key", "variant", "user_id", "event_name", "event_id"),
            "event_receipts" to setOf("event_id", "payload_hash", "config_version"))
        val checked = mutableSetOf<Pair<String, String>>()
        connection.createStatement().use { sql ->
            sql.executeQuery("SELECT TABLE_NAME, COLUMN_NAME, COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()").use { rows ->
                while (rows.next()) {
                    val table = rows.getString(1)
                    val column = rows.getString(2)
                    if (column in expected[table].orEmpty()) {
                        assertEquals("utf8mb4_0900_bin", rows.getString(3), "$table.$column")
                        checked.add(table to column)
                    }
                }
            }
        }
        assertEquals(expected.flatMap { (table, columns) -> columns.map { table to it } }.toSet(), checked)
    }

    private fun scalar(connection: Connection, query: String): Long = connection.createStatement().use { sql ->
        sql.executeQuery(query).use { rows -> check(rows.next()); rows.getLong(1) }
    }
}

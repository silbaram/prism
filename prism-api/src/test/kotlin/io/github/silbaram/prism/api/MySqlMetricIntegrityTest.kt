package io.github.silbaram.prism.api

import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionCommand
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionResult
import io.github.silbaram.prism.api.conversion.application.service.TrackConversionService
import io.github.silbaram.prism.api.conversion.application.port.out.LoadImpressionPort
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.UUID

/** Run with :prism-api:mysqlTest. Creates and drops only its own uniquely named databases. */
@Tag("mysql")
class MySqlMetricIntegrityTest {
    @ParameterizedTest(name = "migrate existing database = {0}")
    @ValueSource(booleans = [false, true])
    fun `fresh and migrated MySQL schemas preserve exact identities and exposure attribution`(migrate: Boolean) {
        val baseUrl = requireNotNull(System.getenv("PRISM_TEST_MYSQL_URL"))
        val username = System.getenv("PRISM_TEST_MYSQL_USERNAME") ?: "root"
        val password = System.getenv("PRISM_TEST_MYSQL_PASSWORD") ?: ""
        val database = "prism_test_${UUID.randomUUID().toString().replace("-", "")}"
        val url = baseUrl.substringBefore('?').trimEnd('/') + "/$database" +
            baseUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
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
                        assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM log_conversion_unattributed_archive"))
                        assertEquals(3L, scalar(connection, "SELECT COUNT(*) FROM log_conversion WHERE impression_id IS NULL"))
                    } else {
                        ScriptUtils.executeSqlScript(connection, ClassPathResource("schema.sql"))
                    }
                    assertExactCollations(connection)
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
                }
            } finally {
                admin.createStatement().use { it.execute("DROP DATABASE $database") }
            }
        }
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
            "variants" to setOf("name"), "log_impression" to setOf("experiment_key", "variant", "user_id"),
            "log_conversion" to setOf("experiment_key", "variant", "user_id", "event_name"))
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

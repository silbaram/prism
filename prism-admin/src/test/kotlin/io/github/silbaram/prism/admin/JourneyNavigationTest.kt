package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.controller.dto.JourneyListSource
import io.github.silbaram.prism.admin.controller.dto.JourneyNavigation
import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDateTime

class JourneyNavigationTest {
    private val from = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val query = JourneyQuery(from, from.plusDays(2), "u & ? + / <script> ", "A ", size = 1)
    private fun values(location: String) = URI(location).rawQuery.split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }

    @Test fun `return locations preserve exact filters cursors and source size under a context path`() {
        val source = JourneyNavigation(JourneyListSource.USERS, true, true, "REACHED", 25, "before + /", "B ").validated()
        val location = source.location("/prism", 1, query, null, 1, FunnelSelection.REACHED)!!
        assertEquals("/prism/admin/experiments/1/journeys", URI(location).rawPath)
        assertEquals(mapOf("from" to query.from.toString(), "until" to query.until.toString(),
            "userId" to query.userId, "variant" to query.variant, "goalState" to "REACHED", "size" to "25",
            "afterUser" to "before + /", "afterVariant" to "B "), values(location))
    }

    @Test fun `unfiltered lists keep the resolved period without adding the selected user or variant`() {
        val source = JourneyNavigation(JourneyListSource.USERS).validated()
        val values = values(source.location("", 1, query, null, 1, FunnelSelection.REACHED)!!)
        assertEquals(query.from.toString(), values["from"])
        assertEquals(query.until.toString(), values["until"])
        assertFalse(values.containsKey("userId")); assertFalse(values.containsKey("variant"))
    }

    @Test fun `funnel return uses the existing query and the source list cursor`() {
        val funnel = FunnelQuery(listOf(" cart ", "한글+ &?/#"), query.from, query.until, 24)
        val source = JourneyNavigation(listSource = JourneyListSource.FUNNEL, listSize = 10, listAfterUser = "이전 & ").validated()
        val location = source.location("/prism", 1, query, funnel, 2, FunnelSelection.MISSING)!!
        assertEquals("/prism/admin/experiments/1/funnel/users", URI(location).rawPath)
        assertEquals(" cart \n한글+ &?/#", values(location)["steps"])
        assertEquals("이전 & ", values(location)["afterUser"])
        assertEquals("MISSING", values(location)["selection"])
        assertEquals("10", values(location)["size"])
    }

    @Test fun `invalid navigation state is rejected and empty optional parameters are normalized`() {
        assertNull(JourneyNavigation().location("", 1, query, null, 1, FunnelSelection.REACHED))
        assertNull(JourneyNavigation(listAfterUser = "", listAfterVariant = "").validated().listAfterUser)
        for (source in listOf(JourneyNavigation(listSize = 0), JourneyNavigation(listSize = 101),
            JourneyNavigation(listGoalState = "BAD"), JourneyNavigation(listAfterUser = " "),
            JourneyNavigation(listSource = JourneyListSource.USERS, listAfterUser = "u"))) {
            assertThrows(AdminValidationException::class.java) { source.validated() }
        }
        assertThrows(AdminValidationException::class.java) {
            JourneyNavigation(listSource = JourneyListSource.FUNNEL).location("", 1, query, null, 1, FunnelSelection.REACHED)
        }
    }

    @Test fun `pattern return preserves depth and limit without accidentally restricting all variants`() {
        for (filtered in listOf(false, true)) {
            val source = JourneyNavigation(listSource = JourneyListSource.PATTERNS,
                listFilterVariant = filtered, patternDepth = 8, patternTop = 2).validated()
            val location = source.location("/prism", 1, query, null, 1, FunnelSelection.REACHED)!!
            assertEquals("/prism/admin/experiments/1/journeys/patterns", URI(location).rawPath)
            assertEquals(query.from.toString(), values(location)["from"])
            assertEquals(query.until.toString(), values(location)["until"])
            assertEquals("8", values(location)["depth"]); assertEquals("2", values(location)["top"])
            assertEquals(query.variant.takeIf { filtered }, values(location)["variant"])
        }
        assertThrows(AdminValidationException::class.java) { JourneyNavigation(patternDepth = 0).validated() }
    }

    @Test fun `pattern user return preserves the exact list page while retaining the original aggregate variant scope`() {
        val key = "ab".repeat(32)
        val precise = query.copy(from = from.plusNanos(123000), until = from.plusDays(2).plusNanos(123000))
        for (filtered in listOf(false, true)) {
            val source = JourneyNavigation(listSource = JourneyListSource.PATTERN_USERS,
                listFilterVariant = filtered, listGoalState = "NOT_REACHED", listSize = 25,
                listAfterUser = "이전 & + / ", patternDepth = 8, patternTop = 3, patternKey = key).validated()
            val location = source.location("/prism", 1, precise, null, 1, FunnelSelection.REACHED)!!
            assertEquals("/prism/admin/experiments/1/journeys/patterns/users", URI(location).rawPath)
            assertEquals(mapOf("from" to precise.from.toString(), "until" to precise.until.toString(),
                "variant" to precise.variant, "depth" to "8", "top" to "3", "pathKey" to key,
                "goalState" to "NOT_REACHED", "size" to "25", "afterUser" to "이전 & + / ",
                "allVariants" to (!filtered).toString()), values(location))
            assertFalse(values(location).containsKey("userId"))
            assertFalse(values(location).containsKey("steps"))
        }
    }

    @Test fun `pattern user navigation rejects missing or malformed structural keys and missing variant`() {
        assertNull(JourneyNavigation(patternKey = "").validated().patternKey)
        for (key in listOf(null, "", "a".repeat(63), "A".repeat(64), "https://example.com/", "<script>")) {
            assertThrows(AdminValidationException::class.java) {
                JourneyNavigation(listSource = JourneyListSource.PATTERN_USERS, patternKey = key).validated()
            }
        }
        val source = JourneyNavigation(listSource = JourneyListSource.PATTERN_USERS, patternKey = "ab".repeat(32)).validated()
        assertThrows(AdminValidationException::class.java) {
            source.location("", 1, query.copy(variant = null), null, 1, FunnelSelection.REACHED)
        }
    }

}

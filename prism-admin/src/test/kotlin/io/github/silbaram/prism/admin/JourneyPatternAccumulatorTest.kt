package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.service.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.FunnelEventRow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class JourneyPatternAccumulatorTest {
    private val start = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val query = JourneyPatternQuery(start, start.plusDays(2))
    private fun row(user: String, name: String, second: Long, variant: String = "A") =
        FunnelEventRow(1, user, variant, name, start.plusSeconds(second))
    private fun feed(target: JourneyPatternAccumulator, user: String, names: List<String>, variant: String = "A") =
        names.forEachIndexed { i, name -> target.accept(row(user, name, i.toLong(), variant)) }

    @Test fun `counts users instead of retries and counts goals beyond a limited prefix`() {
        val accumulator = JourneyPatternAccumulator(query.copy(depth = 3, top = 1), "purchase", listOf("A", "B"))
        for (user in listOf("1", "2", "3", "4")) feed(accumulator, user, listOf("cart", "checkout", "checkout", "purchase", "purchase"))
        feed(accumulator, "5", listOf("cart", "checkout", "checkout"))
        feed(accumulator, "6", listOf("cart", "checkout", "checkout", "failed"))
        val report = accumulator.finish()
        val a = report.groups.first()
        assertEquals(6, a.users); assertEquals(2, a.patternCount); assertEquals(1, a.unlistedUsers)
        val path = a.patterns.single()
        assertEquals(listOf(listOf("cart"), listOf("checkout"), listOf("checkout")), path.path.groups)
        assertTrue(path.path.truncated); assertEquals(5, path.users); assertEquals(4, path.goals)
        assertEquals(80.0, path.goalPercent); assertEquals(500.0 / 6, path.sharePercent, 0.000001)
        assertEquals(listOf("1", "2", "3"), path.sampleUsers)
        assertEquals(0, report.groups.last().users); assertTrue(report.groups.last().patterns.isEmpty())
    }

    @Test fun `simultaneous events are unordered multisets regardless of insertion order`() {
        val accumulator = JourneyPatternAccumulator(query, "purchase", listOf("A"))
        listOf("checkout", "cart", "cart").forEach { accumulator.accept(row("u1", it, 0)) }
        accumulator.accept(row("u1", "purchase", 1))
        listOf("cart", "cart", "checkout").forEach { accumulator.accept(row("u2", it, 0)) }
        accumulator.accept(row("u2", "purchase", 1))
        val pattern = accumulator.finish().groups.single().patterns.single()
        assertEquals(2, pattern.users)
        assertEquals(listOf(listOf("cart", "cart", "checkout"), listOf("purchase")), pattern.path.groups)
        assertFalse(pattern.path.truncated)
    }

    @Test fun `oversized same timestamp groups are never split or arbitrarily ordered`() {
        val accumulator = JourneyPatternAccumulator(query.copy(depth = 2), "purchase", listOf("A"))
        listOf("cart", "checkout", "purchase").forEach { accumulator.accept(row("1", it, 0)) }
        accumulator.accept(row("2", "cart", 0))
        listOf("checkout", "purchase").forEach { accumulator.accept(row("2", it, 1)) }
        feed(accumulator, "3", listOf("cart", "checkout"))
        val group = accumulator.finish().groups.single()
        assertEquals(3, group.users); assertEquals(1, group.excludedUsers)
        val cut = group.patterns.single { it.path.truncated }
        assertEquals(listOf(listOf("cart")), cut.path.groups); assertEquals(100.0, cut.goalPercent)
        assertEquals(100.0 / 3, cut.sharePercent, 0.000001)
        assertFalse(group.patterns.single { it.path.groups.size == 2 }.path.truncated)
    }

    @Test fun `event names cannot collide with delimiters group boundaries case or whitespace`() {
        val accumulator = JourneyPatternAccumulator(query, null, listOf("A"))
        feed(accumulator, "1", listOf("a → b", "c"))
        feed(accumulator, "2", listOf("a", "b → c"))
        feed(accumulator, "3", listOf("a", "b"))
        accumulator.accept(row("4", "a", 0)); accumulator.accept(row("4", "b", 0))
        feed(accumulator, "5", listOf("A", "b"))
        feed(accumulator, "6", listOf("a ", "b"))
        feed(accumulator, "6", listOf("a ", "b"), "A ")
        val groups = accumulator.finish().groups
        assertEquals(6, groups.first().patternCount); assertEquals(1, groups.last().users)
        assertTrue(groups.flatMap { it.patterns }.all { it.goalPercent == null })
    }

    @Test fun `capacity limits reject incomplete reports and permit their exact boundary`() {
        val events = JourneyPatternAccumulator(query, null, listOf("A"), maxEvents = 2)
        feed(events, "1", listOf("cart", "checkout"))
        assertThrows(AdminValidationException::class.java) { events.accept(row("1", "purchase", 2)) }
        val patterns = JourneyPatternAccumulator(query, null, listOf("A"), maxPatterns = 1)
        feed(patterns, "1", listOf("cart")); feed(patterns, "2", listOf("checkout"))
        assertThrows(AdminValidationException::class.java) { patterns.finish() }
        val exact = JourneyPatternAccumulator(query, null, listOf("A"), maxEvents = 2, maxPatterns = 1)
        feed(exact, "1", listOf("cart", "checkout"))
        assertEquals(2, exact.finish().events)
    }

    @Test fun `query rejects unsupported lengths periods and identities`() {
        for (invalid in listOf(query.copy(depth = 1), query.copy(depth = 9), query.copy(top = 0), query.copy(top = 51),
            query.copy(variant = " "), query.copy(until = start), query.copy(until = start.plusDays(367)),
            query.copy(from = start.plusNanos(1)), query.copy(until = LocalDateTime.now().plusDays(1)))) {
            assertThrows(AdminValidationException::class.java) { invalid.validate() }
        }
    }
}

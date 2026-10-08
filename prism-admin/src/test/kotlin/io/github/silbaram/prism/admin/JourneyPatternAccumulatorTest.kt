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

    @Test fun `stable path keys preserve structure repeats exact names and truncation`() {
        val canonical = JourneyPatternPath(listOf(listOf("cart"), listOf("checkout", "checkout")), true)
        assertEquals("e5d72a4537d174599c6073d72426aa2d836dccc165fb570b4cbdf24bf7e9f14f", canonical.key)
        assertEquals(canonical, canonical.copy())
        val different = listOf(canonical, canonical.copy(truncated = false),
            JourneyPatternPath(listOf(listOf("cart", "checkout"), listOf("checkout")), true),
            JourneyPatternPath(listOf(listOf("cart"), listOf("checkout")), true),
            JourneyPatternPath(listOf(listOf("cart → checkout"), listOf("checkout")), true),
            JourneyPatternPath(listOf(listOf("cart"), listOf("checkout → checkout")), true),
            JourneyPatternPath(listOf(listOf("Cart"), listOf("checkout", "checkout")), true),
            JourneyPatternPath(listOf(listOf("cart "), listOf("checkout", "checkout")), true),
            JourneyPatternPath(listOf(listOf("\uD83D\uDE00\u0000\n", "\uE000")), false))
        assertEquals(different.size, different.map { it.key }.toSet().size)
        different.forEach { validatePatternKey(it.key) }
        for (bad in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), " " + canonical.key)) {
            assertThrows(AdminValidationException::class.java) { validatePatternKey(bad) }
        }
        fun classify(names: List<String>): JourneyPatternPath {
            val completed = mutableListOf<JourneyPatternUser>()
            val classifier = JourneyPatternClassifier(5, null, consume = completed::add)
            names.forEach { classifier.accept(row("u", it, 0)) }
            classifier.finish()
            return completed.single().path!!
        }
        assertEquals(classify(listOf("checkout", "cart", "cart")).key, classify(listOf("cart", "cart", "checkout")).key)
    }

    @Test fun `shared classifier completes metadata and goals across batches and beyond its retained prefix`() {
        val completed = mutableListOf<JourneyPatternUser>()
        val classifier = JourneyPatternClassifier(2, "purchase", consume = completed::add)
        val accumulator = JourneyPatternAccumulator(query.copy(depth = 2), "purchase", listOf("A"))
        repeat(1001) { index ->
            val event = row("long", "cart", index.toLong())
            classifier.accept(event, false); accumulator.accept(event)
        }
        val purchase = row("long", "purchase", 1001)
        classifier.accept(purchase, false); accumulator.accept(purchase)
        listOf("cart", "checkout", "purchase").forEach { name ->
            val event = row("overflow", name, 0)
            classifier.accept(event); accumulator.accept(event)
        }
        classifier.finish(); classifier.finish()
        assertEquals(2, completed.size, "Finishing cannot duplicate the last user")
        val long = completed.first()
        assertEquals(start, long.firstAt); assertEquals(start.plusSeconds(1001), long.lastAt)
        assertEquals(1002L, long.events); assertTrue(long.hasGoal); assertFalse(long.afterCursor)
        assertTrue(long.path!!.truncated)
        assertEquals(listOf(listOf("cart"), listOf("cart")), long.path!!.groups)
        val overflow = completed.last()
        assertNull(overflow.path); assertTrue(overflow.hasGoal); assertEquals(3L, overflow.events)
        assertEquals(start, overflow.firstAt); assertEquals(start, overflow.lastAt)
        val report = accumulator.finish().groups.single()
        assertEquals(1L, report.excludedUsers)
        assertEquals(long.path, report.patterns.single().path)
        assertEquals(1L, report.patterns.single().goals)
    }

    @Test fun `path user totals include every history while pagination follows database cursor flags`() {
        val path = JourneyPatternPath(listOf(listOf("cart"), listOf("checkout")), true)
        val selected = JourneyPatternUserAccumulator(path.key, "purchase", "REACHED", 1)
        val classifier = JourneyPatternClassifier(2, "purchase", consume = selected::accept)
        // MySQL utf8mb4_0900_bin orders U+E000 before supplementary characters; Kotlin String comparison differs.
        for ((user, hasGoal, after) in listOf(Triple("\uE000", false, false), Triple("\uD83D\uDE00", true, true),
            Triple("\uDBFF\uDFFF", true, true))) {
            listOf("cart", "checkout", if (hasGoal) "purchase" else "failed").forEachIndexed { index, name ->
                classifier.accept(row(user, name, index.toLong()), after)
            }
        }
        classifier.finish()
        val users = selected.finish()
        assertEquals(3L, users.totalUsers); assertEquals(2L, users.goalUsers); assertEquals(2L, users.matchedUsers)
        assertEquals(listOf("\uD83D\uDE00"), users.items.map { it.userId })
        assertEquals("\uD83D\uDE00", users.nextUser)
        assertEquals(path, users.path)
        val none = JourneyPatternUserAccumulator("0".repeat(64), null, "ALL", 1)
        users.items.forEach(none::accept)
        assertTrue(none.finish().items.isEmpty()); assertNull(none.finish().path)
        assertNull(none.finish().goalUsers); assertEquals(0L, none.finish().totalUsers)
    }

    @Test fun `single path user selection does not impose the report distinct pattern limit`() {
        val path = JourneyPatternPath(listOf(listOf("selected")), false)
        val selected = JourneyPatternUserAccumulator(path.key, null, "ALL", 1)
        val classifier = JourneyPatternClassifier(2, null, consume = selected::accept)
        repeat(5001) { classifier.accept(row(it.toString().padStart(5, '0'), "different-$it", 0)) }
        classifier.accept(row("last", "selected", 0)); classifier.finish()
        assertEquals(listOf("last"), selected.finish().items.map { it.userId })
        assertEquals(1L, selected.finish().totalUsers); assertNull(selected.finish().nextUser)
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

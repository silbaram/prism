package io.github.silbaram.prism.core.splitter

import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.time.Instant

class TrafficAllocationTest : FunSpec({
    val base = Experiment("allocation", listOf(Variant("A", 50), Variant("B", 50)))
    test("participation expansion retains existing variants and independently splits the admitted sample") {
        var admitted = 0
        var a = 0
        repeat(20_000) { index ->
            val user = "u-$index"
            TrafficSplitter.assign(base.copy(trafficAllocation = 0), user) shouldBe null
            val small = TrafficSplitter.assign(base.copy(trafficAllocation = 5), user)
            val medium = TrafficSplitter.assign(base.copy(trafficAllocation = 40), user)
            val full = TrafficSplitter.assign(base, user)
            if (small != null) {
                admitted++
                if (small.name == "A") a++
                small shouldBe medium
                small shouldBe full
            }
            if (medium != null) medium shouldBe full
        }
        (admitted in 850..1150) shouldBe true
        (a.toDouble() / admitted in 0.43..0.57) shouldBe true
    }
    test("period includes start and excludes end even with an unchanged cached experiment") {
        val start = Instant.parse("2026-09-13T00:00:00Z")
        val end = start.plusSeconds(60)
        val scheduled = base.copy(startsAt = start, endsAt = end)
        TrafficSplitter.assign(scheduled, "u", now = start.minusNanos(1)) shouldBe null
        TrafficSplitter.assign(scheduled, "u", now = start) shouldBe TrafficSplitter.assign(base, "u")
        TrafficSplitter.assign(scheduled, "u", now = end.minusNanos(1)) shouldBe TrafficSplitter.assign(base, "u")
        TrafficSplitter.assign(scheduled, "u", now = end) shouldBe null
    }
    test("invalid participation and periods are rejected at domain construction") {
        shouldThrow<IllegalArgumentException> { base.copy(trafficAllocation = -1) }
        shouldThrow<IllegalArgumentException> { base.copy(trafficAllocation = 101) }
        shouldThrow<IllegalArgumentException> { base.copy(startsAt = Instant.EPOCH, endsAt = Instant.EPOCH) }
    }
})

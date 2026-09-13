package io.github.silbaram.prism.core.splitter

import io.github.silbaram.prism.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class PopulationTest : FunSpec({
    test("disjoint layer ranges are exclusive and permanent holdout excludes every experiment") {
        val holdout = HoldoutPolicy("cohort", 500)
        val first = Experiment("first", listOf(Variant("A", 100)), layer = LayerAllocation("checkout", 0, 5000), holdout = holdout)
        val second = Experiment("second", listOf(Variant("B", 100)), layer = LayerAllocation("checkout", 5000, 10000), holdout = holdout)
        val independent = Experiment("independent", listOf(Variant("C", 100)), holdout = holdout)
        var excluded = 0
        var a = 0
        repeat(20_000) {
            val user = "u-$it"
            val assigned = listOf(first, second).count { exp -> TrafficSplitter.assign(exp, user) != null }
            if (holdout.excludes(user)) {
                excluded++
                assigned shouldBe 0
                TrafficSplitter.assign(independent, user) shouldBe null
            } else {
                assigned shouldBe 1
                if (TrafficSplitter.assign(first, user) != null) a++
            }
        }
        (excluded in 850..1150) shouldBe true
        (a in 9000..10000) shouldBe true
    }
    test("invalid layer and holdout definitions fail before serving traffic") {
        shouldThrow<IllegalArgumentException> { LayerAllocation("layer", 5000, 5000) }
        shouldThrow<IllegalArgumentException> { LayerAllocation("layer", 0, 10001) }
        shouldThrow<IllegalArgumentException> { HoldoutPolicy("cohort", -1) }
    }
})

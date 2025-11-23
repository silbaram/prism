package com.prism.core.splitter

// TrafficSplitter가 사용자별 결정성을 유지하고 트래픽 가중치를 충실히 따르는지 검증하는 테스트입니다.

import com.prism.core.model.Experiment
import com.prism.core.model.Variant
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class TrafficSplitterTest : FunSpec({

    test("동일 사용자에게 항상 동일 변형을 할당한다") {
        val variants = listOf(Variant("A", 50), Variant("B", 50))
        val experiment = Experiment("test-exp", variants)

        val userId = "user-123"
        val firstAssignment = TrafficSplitter.assign(experiment, userId)
        val secondAssignment = TrafficSplitter.assign(experiment, userId)

        firstAssignment shouldBe secondAssignment
    }

    test("가중치 비율에 따라 분배한다") {
        val variants = listOf(Variant("A", 30), Variant("B", 70))
        val experiment = Experiment("distribution-test", variants)

        val totalUsers = 10_000
        val results = mutableMapOf<String, Int>()
        repeat(totalUsers) { idx ->
            val variant = TrafficSplitter.assign(experiment, "user-${idx + 1}")
            variant?.let { currentVariant ->
                results[currentVariant.name] = results.getOrDefault(currentVariant.name, 0) + 1
            }
        }

        results.keys.shouldContainAll("A", "B")

        val ratioA = (results["A"] ?: 0).toDouble() / totalUsers
        val ratioB = (results["B"] ?: 0).toDouble() / totalUsers

        ratioA shouldBe (0.3 plusOrMinus 0.02)
        ratioB shouldBe (0.7 plusOrMinus 0.02)
    }
})

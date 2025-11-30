package io.github.silbaram.prism.core.splitter

import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * TrafficSplitter 단위 테스트
 *
 * 이 테스트 클래스는 트래픽 분배 로직의 일관성과 정확성을 검증합니다.
 * 주요 테스트 항목:
 * 1. 분배 일관성 (Deterministic Allocation):
 *    - 동일한 사용자 ID와 실험 키에 대해 항상 동일한 변형(Variant)이 할당되는지 확인합니다.
 *    - 해시 기반 분배 알고리즘의 결정론적 특성을 검증합니다.
 * 2. 가중치 기반 분배 정확성:
 *    - 대량의 사용자(예: 10,000명)를 대상으로 시뮬레이션하여, 각 변형에 할당된 비율이 설정된 가중치와 근사한지(오차 범위 내) 확인합니다.
 *    - 예: A(30%), B(70%) 설정 시 실제 할당 비율이 0.3, 0.7에 수렴하는지 검증합니다.
 */
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

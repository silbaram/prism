package io.github.silbaram.prism.core.splitter

import io.github.silbaram.prism.core.hashing.MurmurHash
import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.targeting.RuleEvaluator
import io.github.silbaram.prism.core.targeting.UserContext
import kotlin.math.abs

/**
 * A/B 테스트 트래픽 분배기.
 *
 * 이 객체는 사용자를 실험의 여러 변형(Variant)에 일관되게 할당하는 역할을 합니다.
 * MurmurHash3 해시 함수를 사용하여 사용자를 균등하게 분산시키며,
 * 동일한 사용자는 항상 동일한 변형에 할당됩니다.
 *
 * ## 작동 원리
 * 1. 타겟팅 규칙 검증: 사용자가 실험 대상인지 확인
 * 2. 해시 계산: (실험키 + 사용자ID)로 해시값 생성
 * 3. 버킷 계산: 해시값을 0-99 범위로 정규화
 * 4. 변형 할당: 버킷 위치에 따라 가중치 기반으로 변형 선택
 *
 * ## 예시
 * ```kotlin
 * val experiment = Experiment(
 *     key = "button-color-test",
 *     variants = listOf(
 *         Variant("red", 40),    // 40%
 *         Variant("blue", 60)    // 60%
 *     )
 * )
 * val variant = TrafficSplitter.assign(experiment, "user-123")
 * // user-123은 항상 동일한 변형에 할당됨
 * ```
 *
 * @see MurmurHash
 * @see Experiment
 * @see Variant
 */
object TrafficSplitter {

    /**
     * 버킷의 총 개수 (0-99).
     * 각 변형의 가중치는 이 버킷들 중 몇 개를 차지할지 결정합니다.
     */
    private const val BUCKET_COUNT = 100

    /**
     * 사용자를 실험의 특정 변형에 할당합니다.
     *
     * 이 메서드는 다음 단계를 거쳐 사용자를 할당합니다:
     * 1. 타겟팅 규칙이 있다면 먼저 검증
     * 2. 사용자 ID와 실험 키를 결합하여 해시 생성
     * 3. 해시를 0-99 버킷으로 변환
     * 4. 버킷 위치에 따라 가중치 기반으로 변형 선택
     *
     * ## 일관성 보장
     * 동일한 사용자와 실험에 대해 항상 동일한 결과를 반환합니다.
     * 이는 해시 함수의 결정론적 특성에 기반합니다.
     *
     * ## 독립성 보장
     * 실험 키를 해시에 포함시켜, 같은 사용자라도 다른 실험에서는
     * 독립적으로 다른 변형에 할당될 수 있습니다.
     *
     * @param experiment 사용자를 할당할 실험
     * @param userId 고유한 사용자 식별자
     * @param context 타겟팅 규칙 평가에 사용될 사용자 컨텍스트 (선택적)
     * @return 할당된 변형, 타겟팅 규칙을 만족하지 않으면 null
     *
     * @throws IllegalArgumentException 실험에 변형이 없는 경우
     */
    fun assign(
        experiment: Experiment,
        userId: String,
        context: UserContext = UserContext(emptyMap())
    ): Variant? {
        require(experiment.variants.isNotEmpty()) { "Experiment must have at least one variant" }

        // 1단계: 타겟팅 규칙 검증
        // 실험에 타겟팅 규칙이 있다면, 모든 규칙을 만족하는지 확인
        if (experiment.targetingRules.isNotEmpty()) {
            val isTargeted = experiment.targetingRules.all { rule ->
                RuleEvaluator.evaluate(rule, context)
            }
            if (!isTargeted) {
                return null // 타겟팅 대상이 아님
            }
        }

        // 2단계: 사용자 버킷 계산
        val bucket = calculateBucket(experiment.key, userId)

        // 3단계: 가중치 기반 변형 할당
        return selectVariantByBucket(experiment.variants, bucket)
    }

    /**
     * 실험 키와 사용자 ID를 조합하여 0-99 범위의 버킷을 계산합니다.
     *
     * ## 동작 방식
     * 1. 실험 키와 사용자 ID를 결합 (예: "button-test:user-123")
     * 2. MurmurHash3로 해시값 생성
     * 3. 해시값의 절대값을 100으로 나눈 나머지 계산 (0-99)
     *
     * ## 실험 간 독립성
     * 실험 키를 포함시킴으로써, 같은 사용자가 서로 다른 실험에서
     * 독립적으로 분배될 수 있습니다.
     * - 실험 A: "user-123" → bucket 45
     * - 실험 B: "user-123" → bucket 73
     *
     * @param experimentKey 실험의 고유 키
     * @param userId 사용자의 고유 식별자
     * @return 0부터 99까지의 버킷 번호
     */
    private fun calculateBucket(experimentKey: String, userId: String): Int {
        // 실험 키와 사용자 ID를 결합하여 실험별로 다른 해시 생성
        val hashKey = "$experimentKey:$userId"
        val hash = MurmurHash.hash32(hashKey)

        // 해시값을 0-99 범위로 정규화
        // abs()를 사용하여 음수 해시값을 양수로 변환
        return abs(hash) % BUCKET_COUNT
    }

    /**
     * 버킷 위치에 따라 적절한 변형을 선택합니다.
     *
     * ## 동작 방식
     * 변형들의 가중치를 누적하면서, 버킷이 어느 구간에 속하는지 확인합니다.
     *
     * 예시: A(40%), B(60%)
     * - bucket 0-39 → A 선택
     * - bucket 40-99 → B 선택
     *
     * ## 가중치 누적
     * ```
     * Variant A (weight: 40)
     *   currentWeight: 0 → 40
     *   bucket < 40 → A 선택
     *
     * Variant B (weight: 60)
     *   currentWeight: 40 → 100
     *   bucket < 100 → B 선택
     * ```
     *
     * @param variants 실험의 변형 목록
     * @param bucket 0-99 범위의 버킷 번호
     * @return 선택된 변형
     */
    private fun selectVariantByBucket(variants: List<Variant>, bucket: Int): Variant {
        var currentWeight = 0

        for (variant in variants) {
            currentWeight += variant.weight
            // 버킷이 현재 누적 가중치보다 작으면 이 변형 선택
            if (bucket < currentWeight) {
                return variant
            }
        }

        // 가중치 합이 100이라면 여기 도달하지 않아야 함
        // 하지만 안전을 위해 마지막 변형 반환
        return variants.last()
    }
}

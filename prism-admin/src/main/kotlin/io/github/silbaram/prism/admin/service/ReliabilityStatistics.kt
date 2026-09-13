package io.github.silbaram.prism.admin.service

import org.apache.commons.math3.special.Gamma

enum class SrmStatus { PASS, MISMATCH, INSUFFICIENT_DATA, INVALID_DATA }
data class SrmResult(val status: SrmStatus, val message: String, val statistic: Double? = null,
    val pValue: Double? = null, val degreesOfFreedom: Int? = null, val threshold: Double = 0.0005)
enum class ComparisonStatus { AVAILABLE, NOT_ENDED, BLOCKED, INSUFFICIENT_DATA }
data class GroupComparison(val status: ComparisonStatus, val message: String, val statistic: Double? = null,
    val pValue: Double? = null, val degreesOfFreedom: Int? = null)

/** Pearson goodness-of-fit on unique users; weights are fixed before collection, hence df = k - 1.
 * https://www.itl.nist.gov/div898/software/dataplot/refman1/auxillar/chsqgood.htm
 */
internal fun sampleRatioMismatch(weights: List<Pair<String, Int>>, observed: Map<String, Long>, uniqueUsers: Long): SrmResult {
    if (weights.isEmpty() || weights.map { it.first }.distinct().size != weights.size ||
        weights.any { it.first.isBlank() || it.first.length > 255 || it.second !in 0..100 } ||
        weights.sumOf { it.second.toLong() } != 100L ||
        observed.any { it.value < 0 } || uniqueUsers < 0) {
        return SrmResult(SrmStatus.INVALID_DATA, "실험 가중치 또는 관측 데이터가 올바르지 않습니다.")
    }
    val positive = weights.filter { it.second > 0 }
    val allowed = positive.map { it.first }.toSet()
    if (observed.any { it.value > 0 && it.key !in allowed }) {
        return SrmResult(SrmStatus.INVALID_DATA, "설정에 없거나 가중치가 0%인 변형에 노출이 있습니다.")
    }
    val total = observed.values.sumOf { it.toDouble() }
    if (total != uniqueUsers.toDouble()) {
        return SrmResult(SrmStatus.INVALID_DATA, "동일 사용자가 여러 변형에 노출되었습니다. 실험 결과를 해석할 수 없습니다.")
    }
    if (positive.size < 2 || positive.any { total * it.second / 100 < 5 }) {
        return SrmResult(SrmStatus.INSUFFICIENT_DATA, "양수 가중치 변형이 2개 이상이고 각 기대 사용자 수가 5 이상이어야 합니다.")
    }
    val statistic = positive.sumOf { (name, weight) ->
        val expected = total * weight / 100
        val delta = (observed[name] ?: 0).toDouble() - expected
        delta * delta / expected
    }
    val df = positive.size - 1
    val p = chiSquareSurvival(statistic, df)
    return if (p < 0.0005) SrmResult(SrmStatus.MISMATCH,
        "SRM 경고: 기대 배정 비율과 관측 비율이 다릅니다. 수집·배정 문제를 조사한 뒤 결과를 해석하세요.", statistic, p, df)
    else SrmResult(SrmStatus.PASS, "SRM 미감지 (데이터 품질 전체를 보증하지는 않습니다).", statistic, p, df)
}

/** One omnibus 2 x k Pearson independence test, not pairwise winner selection.
 * https://www.itl.nist.gov/div898/handbook/prc/section4/prc45.htm
 * Fixed-horizon only: no sequential/optional-stopping correction.
 */
internal fun compareConversionRates(stats: List<VariantStats>, srm: SrmResult, ended: Boolean): GroupComparison {
    if (srm.status != SrmStatus.PASS) return GroupComparison(ComparisonStatus.BLOCKED, "SRM 검사를 통과한 데이터에서만 그룹 간 검정을 제공합니다.")
    if (!ended) return GroupComparison(ComparisonStatus.NOT_ENDED, "사전에 정한 수집 기간과 전환 관측 기간을 마치고 실험을 종료한 뒤 확인하세요.")
    // The caller supplies every positive-weight group, including groups with no observations.
    val groups = stats
    if (groups.size < 2 || groups.any { it.conversions == null || it.conversions !in 0..it.impressions }) {
        return GroupComparison(ComparisonStatus.BLOCKED, "목표 이벤트와 유효한 노출·전환 데이터가 필요합니다.")
    }
    if (groups.any { it.impressions == 0L }) {
        return GroupComparison(ComparisonStatus.INSUFFICIENT_DATA, "가중치가 있는 모든 변형에 노출·전환 표본이 필요합니다.")
    }
    val total = groups.sumOf { it.impressions.toDouble() }
    val pooled = groups.sumOf { it.conversions!!.toDouble() } / total
    if (groups.any { it.impressions * pooled < 5 || it.impressions * (1 - pooled) < 5 }) {
        return GroupComparison(ComparisonStatus.INSUFFICIENT_DATA, "각 변형의 기대 전환·미전환 사용자 수가 각각 5 이상이어야 합니다.")
    }
    val statistic = groups.sumOf {
        val expected = it.impressions * pooled
        val delta = it.conversions!! - expected
        delta * delta / expected + delta * delta / (it.impressions * (1 - pooled))
    }
    val df = groups.size - 1
    return GroupComparison(ComparisonStatus.AVAILABLE,
        "모든 변형의 목표 전환율이 같다는 가설에 대한 전체 검정입니다. 개별 승자를 뜻하지 않습니다.",
        statistic, chiSquareSurvival(statistic, df), df)
}

private fun chiSquareSurvival(statistic: Double, df: Int): Double =
    Gamma.regularizedGammaQ(df / 2.0, statistic / 2.0).coerceIn(0.0, 1.0)

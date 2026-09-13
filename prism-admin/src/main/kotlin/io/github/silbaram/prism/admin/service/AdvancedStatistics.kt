package io.github.silbaram.prism.admin.service

import org.apache.commons.math3.distribution.BetaDistribution
import org.apache.commons.math3.random.Well19937c
import kotlin.math.*

/** Stable streaming covariance; no cancellation from subtracting large raw baseline sums. */
class AnalysisMoments {
    var n = 0L; private set
    var successes = 0L; private set
    var baselineCount = 0L; private set
    var meanX = 0.0; private set
    var meanY = 0.0; private set
    var m2X = 0.0; private set
    var m2Y = 0.0; private set
    var cross = 0.0; private set
    fun add(converted: Boolean, baseline: Double?) {
        n++
        if (converted) successes++
        if (baseline == null) return
        require(baseline.isFinite())
        baselineCount++
        val y = if (converted) 1.0 else 0.0
        val dx = baseline - meanX
        val dy = y - meanY
        meanX += dx / baselineCount
        meanY += dy / baselineCount
        m2X += dx * (baseline - meanX)
        m2Y += dy * (y - meanY)
        cross += dx * (y - meanY)
    }
    val rate: Double? get() = if (n == 0L) null else successes.toDouble() / n
}

data class AnalysisInterval(val lower: Double, val upper: Double)
data class BayesianEffect(val probabilityBetter: Double, val meanDifference: Double, val interval: AnalysisInterval,
    val expectedLoss: Double, val draws: Int = 32768, val probabilityMonteCarloError: Double)
data class CupedEffect(val available: Boolean, val message: String, val theta: Double? = null,
    val rawDifference: Double? = null, val adjustedDifference: Double? = null,
    val rawStandardError: Double? = null, val adjustedStandardError: Double? = null, val varianceReduction: Double? = null)

/** Hoeffding + union bound over every n >= 1 and every arm.
 * delta(n, arm) = alpha / (arms * n * (n+1)); its sum over n and arms is alpha.
 * Assumes independent user-level bounded outcomes with a stable arm mean. This conservative
 * confidence sequence permits arbitrary repeat viewing; a pointwise Wilson interval does not.
 * https://www.stat.cmu.edu/~cshalizi/sml/21/lectures/06/lecture-06.html
 */
internal fun sequentialInterval(successes: Long, n: Long, arms: Int, alpha: Double = 0.05): AnalysisInterval {
    require(n >= 0 && successes in 0..n && arms >= 2 && alpha > 0 && alpha < 1)
    if (n == 0L) return AnalysisInterval(0.0, 1.0)
    val width = sqrt((ln(2.0 * arms / alpha) + ln(n.toDouble()) + ln(n.toDouble() + 1)) / (2 * n.toDouble()))
    val rate = successes.toDouble() / n
    return AnalysisInterval(max(0.0, rate - width), min(1.0, rate + width))
}

internal fun sequentialDifference(control: AnalysisMoments, treatment: AnalysisMoments, arms: Int): AnalysisInterval {
    val a = sequentialInterval(control.successes, control.n, arms)
    val b = sequentialInterval(treatment.successes, treatment.n, arms)
    return AnalysisInterval(b.lower - a.upper, b.upper - a.lower)
}

/** Independent Beta(1,1) priors; posterior draws are deterministic for reproducible rendering.
 * Probability is Monte Carlo (worst-case standard error < 0.28 percentage points).
 */
internal fun bayesianEffect(control: AnalysisMoments, treatment: AnalysisMoments): BayesianEffect? {
    if (control.n == 0L || treatment.n == 0L) return null
    val random = Well19937c(0x505249534DL)
    val a = BetaDistribution(random, control.successes + 1.0, control.n - control.successes + 1.0)
    val b = BetaDistribution(random, treatment.successes + 1.0, treatment.n - treatment.successes + 1.0)
    val differences = DoubleArray(32768) { b.sample() - a.sample() }
    val probability = differences.count { it > 0 }.toDouble() / differences.size
    val loss = differences.sumOf { max(0.0, -it) } / differences.size
    differences.sort()
    return BayesianEffect(probability, b.numericalMean - a.numericalMean,
        AnalysisInterval(differences[(differences.size * 0.025).toInt()], differences[(differences.size * 0.975).toInt()]),
        loss, differences.size, 0.5 / sqrt(differences.size.toDouble()))
}

/** Within-arm centered slope prevents between-arm treatment differences from estimating theta.
 * Diagnostic plug-in standard errors only: no sequential significance claim for CUPED.
 */
internal fun cupedEffect(control: AnalysisMoments, treatment: AnalysisMoments): CupedEffect {
    if (control.n != control.baselineCount || treatment.n != treatment.baselineCount)
        return CupedEffect(false, "전체 사용자에게 유효한 사전 지표가 필요합니다. 누락 사용자를 제외한 분석은 하지 않습니다.")
    if (min(control.n, treatment.n) < 30) return CupedEffect(false, "각 변형에 관측 완료 사용자 30명 이상이 필요합니다.")
    val xx = control.m2X + treatment.m2X
    if (xx <= 0.0 || !xx.isFinite()) return CupedEffect(false, "사전 지표의 분산이 없어 보정할 수 없습니다.")
    val theta = (control.cross + treatment.cross) / xx
    if (!theta.isFinite()) return CupedEffect(false, "사전 지표의 수치 범위로 인해 안정적으로 보정할 수 없습니다.")
    fun residual(group: AnalysisMoments): Double {
        val projected = theta * sqrt(max(0.0, group.m2X))
        return max(0.0, group.m2Y - 2 * theta * group.cross + projected * projected) / (group.n - 2)
    }
    val rawVariance = control.m2Y / (control.n - 1) / control.n + treatment.m2Y / (treatment.n - 1) / treatment.n
    val adjustedVariance = residual(control) / control.n + residual(treatment) / treatment.n
    val raw = treatment.rate!! - control.rate!!
    return CupedEffect(true, "CUPED 보정 효과와 근사 표준오차입니다. 순차 검정이나 자동 승자 판정에 사용하지 않습니다.",
        theta, raw, raw - theta * (treatment.meanX - control.meanX), sqrt(rawVariance), sqrt(adjustedVariance),
        if (rawVariance > 0) 1 - adjustedVariance / rawVariance else null)
}

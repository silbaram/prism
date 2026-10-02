package io.github.silbaram.prism.admin.controller.dto

import io.github.silbaram.prism.admin.controller.journeyTime
import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.admin.service.SavedFunnelInput
import io.github.silbaram.prism.admin.service.SavedFunnelPeriod
import io.github.silbaram.prism.admin.service.SavedFunnelView

/** Keep raw values on validation/conflict responses, especially dates and the submitted version. */
data class SavedFunnelForm(val name: String = "", val description: String = "", val steps: String = "",
    val windowHours: String = "24", val periodMode: String = "LAST_30_DAYS", val from: String = "",
    val until: String = "", val version: String = "") {
    val fromInputType: String get() = utcDateTimeInputType(from)
    val untilInputType: String get() = utcDateTimeInputType(until)

    fun input(): SavedFunnelInput {
        require(steps.length <= 4096) { "퍼널 단계 입력은 4,096자 이하여야 합니다." }
        val period = SavedFunnelPeriod.entries.find { it.name == periodMode }
            ?: throw AdminValidationException("조회 기간 유형을 확인하세요.")
        val hours = windowHours.toIntOrNull() ?: throw AdminValidationException("전환 제한 시간을 정수로 입력하세요.")
        val fixed = period == SavedFunnelPeriod.FIXED
        return SavedFunnelInput(name, description, steps.lines().filter(String::isNotBlank), hours, period,
            if (fixed) from.takeIf(String::isNotBlank)?.let(::journeyTime) else null,
            if (fixed) until.takeIf(String::isNotBlank)?.let(::journeyTime) else null)
    }

    fun parsedVersion(): Long = version.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw AdminValidationException("변경 기준 버전을 확인하세요. 최신 설정을 다시 열어주세요.")

    companion object {
        fun from(saved: SavedFunnelView) = SavedFunnelForm(saved.name, saved.description, saved.steps.joinToString("\n"),
            saved.windowHours.toString(), saved.periodMode.name, saved.fromAt?.toString().orEmpty(),
            saved.untilAt?.toString().orEmpty(), saved.version.toString())
    }
}

package io.github.silbaram.prism.admin.controller.dto

import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.admin.service.FunnelQuery
import io.github.silbaram.prism.admin.service.FunnelSelection
import io.github.silbaram.prism.admin.service.JourneyQuery
import io.github.silbaram.prism.admin.service.validateJourneyIdentity
import io.github.silbaram.prism.admin.service.validatePatternOptions
import io.github.silbaram.prism.admin.service.validatePatternKey
import java.net.URLEncoder

enum class JourneyListSource { USERS, FUNNEL, PATTERNS, PATTERN_USERS }

/** Carry only list state missing from the timeline query; never nest a complete URL or repeat steps. */
data class JourneyNavigation(
    val listSource: JourneyListSource? = null,
    val listFilterUser: Boolean = false,
    val listFilterVariant: Boolean = false,
    val listGoalState: String = "ALL",
    val listSize: Int = 50,
    val listAfterUser: String? = null,
    val listAfterVariant: String? = null,
    val patternDepth: Int = 5,
    val patternTop: Int = 20,
    val patternKey: String? = null
) {
    fun validated(): JourneyNavigation {
        // Thymeleaf can emit empty optional parameters. Empty IDs are not valid identities.
        val navigation = copy(listAfterUser = listAfterUser?.takeUnless(String::isEmpty),
            listAfterVariant = listAfterVariant?.takeUnless(String::isEmpty), patternKey = patternKey?.takeUnless(String::isEmpty))
        require(listSize in 1..100 && listGoalState in setOf("ALL", "REACHED", "NOT_REACHED")) { "목록 복귀 조건을 확인하세요." }
        validateJourneyIdentity(navigation.listAfterUser); validateJourneyIdentity(navigation.listAfterVariant)
        validatePatternOptions(patternDepth, patternTop)
        if (navigation.patternKey != null) validatePatternKey(navigation.patternKey)
        require(listSource != JourneyListSource.PATTERN_USERS || navigation.patternKey != null) { "목록 복귀에 필요한 경로 조건을 확인하세요." }
        if (listSource == JourneyListSource.USERS) {
            require((navigation.listAfterUser == null) == (navigation.listAfterVariant == null)) { "목록 복귀 조건을 확인하세요." }
        }
        return navigation
    }

    fun location(contextPath: String, id: Long, query: JourneyQuery, funnel: FunnelQuery?,
                 stage: Int, selection: FunnelSelection): String? {
        if (listSource == null) return null
        val parameters = linkedMapOf<String, Any?>()
        val path = when (listSource) {
            JourneyListSource.USERS -> {
                parameters.putAll(mapOf("from" to query.from, "until" to query.until,
                    "userId" to query.userId.takeIf { listFilterUser }, "variant" to query.variant.takeIf { listFilterVariant },
                    "goalState" to listGoalState, "size" to listSize,
                    "afterUser" to listAfterUser, "afterVariant" to listAfterVariant))
                "journeys"
            }
            JourneyListSource.FUNNEL -> {
                require(funnel != null) { "목록 복귀에 필요한 퍼널 조건을 확인하세요." }
                parameters.putAll(mapOf("steps" to funnel!!.steps.joinToString("\n"), "from" to query.from, "until" to query.until,
                    "windowHours" to funnel.windowHours, "variant" to query.variant, "stage" to stage,
                    "selection" to selection.name, "size" to listSize, "afterUser" to listAfterUser))
                "funnel/users"
            }
            JourneyListSource.PATTERNS -> {
                parameters.putAll(mapOf("from" to query.from, "until" to query.until,
                    "variant" to query.variant.takeIf { listFilterVariant }, "depth" to patternDepth, "top" to patternTop))
                "journeys/patterns"
            }
            JourneyListSource.PATTERN_USERS -> {
                require(query.variant != null && patternKey != null) { "목록 복귀에 필요한 경로와 변형을 확인하세요." }
                validatePatternKey(patternKey!!)
                parameters.putAll(mapOf("from" to query.from, "until" to query.until, "variant" to query.variant,
                    "depth" to patternDepth, "top" to patternTop, "pathKey" to patternKey,
                    "goalState" to listGoalState, "size" to listSize, "afterUser" to listAfterUser,
                    "allVariants" to !listFilterVariant))
                "journeys/patterns/users"
            }
        }
        val encoded = parameters.filterValues { it != null }.entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value.toString(), Charsets.UTF_8)}"
        }
        return "$contextPath/admin/experiments/$id/$path?$encoded"
    }
}

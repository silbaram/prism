package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.controller.dto.JourneyListSource
import io.github.silbaram.prism.admin.controller.dto.JourneyNavigation
import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.JourneyPatternQuery
import io.github.silbaram.prism.admin.service.JourneyPatternService
import io.github.silbaram.prism.admin.service.JourneyQuery
import io.github.silbaram.prism.admin.service.FunnelSelection
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

private fun resolvePatternVariant(variant: String?, variantToken: String?): String {
    require((variant != null) != (variantToken != null)) { "조회할 변형을 하나만 지정하세요." }
    if (variant != null) return variant
    val token = variantToken!!
    // A 255-code-unit identity takes at most 765 UTF-8 bytes (1,020 base64 characters).
    require(token.length in 1..1020 && token.matches(Regex("[A-Za-z0-9_-]+"))) { "변형 조회 조건을 확인하세요." }
    val bytes = try { Base64.getUrlDecoder().decode(token) }
        catch (_: IllegalArgumentException) { throw AdminValidationException("변형 조회 조건을 확인하세요.") }
    return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: CharacterCodingException) { throw AdminValidationException("변형 조회 조건을 확인하세요.") }
}

@Controller
class JourneyPatternController(private val experiments: ExperimentService, private val patterns: JourneyPatternService) {
    @GetMapping("/admin/experiments/{id}/journeys/patterns")
    fun index(@PathVariable id: Long, @RequestParam(required = false) from: String?,
        @RequestParam(required = false) until: String?, @RequestParam(required = false) variant: String?,
        @RequestParam(defaultValue = "5") depth: Int, @RequestParam(defaultValue = "20") top: Int, model: Model): String {
        val now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        val query = JourneyPatternQuery(from?.let(::journeyTime) ?: now.minusDays(30), until?.let(::journeyTime) ?: now,
            variant?.takeUnless(String::isEmpty), depth, top)
        val experiment = experiments.getExperimentById(id)
        val report = patterns.report(id, query)
        model.addAttribute("experiment", experiment)
        model.addAttribute("query", query)
        model.addAttribute("report", report)
        model.addAttribute("navigation", JourneyNavigation(listSource = JourneyListSource.PATTERNS,
            listFilterVariant = query.variant != null, patternDepth = depth, patternTop = top))
        return "journey/patterns"
    }

    @GetMapping("/admin/experiments/{id}/journeys/patterns/users")
    fun users(@PathVariable id: Long, @RequestParam from: String, @RequestParam until: String,
        @RequestParam(required = false) variant: String?, @RequestParam(required = false) variantToken: String?,
        @RequestParam pathKey: String,
        @RequestParam(defaultValue = "5") depth: Int, @RequestParam(defaultValue = "20") top: Int,
        @RequestParam(defaultValue = "ALL") goalState: String, @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) afterUser: String?, @RequestParam(defaultValue = "false") allVariants: Boolean,
        request: HttpServletRequest, model: Model): String {
        val selectedVariant = resolvePatternVariant(variant, variantToken)
        val query = JourneyPatternQuery(journeyTime(from), journeyTime(until), selectedVariant, depth, top)
        val cursor = afterUser?.takeUnless(String::isEmpty)
        val users = patterns.users(id, query, pathKey, goalState, cursor, size)
        val navigation = JourneyNavigation(listSource = JourneyListSource.PATTERN_USERS,
            listFilterVariant = !allVariants, listGoalState = goalState, listSize = size, listAfterUser = cursor,
            patternDepth = depth, patternTop = top, patternKey = pathKey)
        val aggregate = JourneyNavigation(listSource = JourneyListSource.PATTERNS, listFilterVariant = !allVariants,
            patternDepth = depth, patternTop = top)
        model.addAttribute("experiment", experiments.getExperimentById(id))
        model.addAttribute("query", query)
        // HTML form submission normalizes CR/LF, so transport the exact identity as ASCII.
        model.addAttribute("variantToken", Base64.getUrlEncoder().withoutPadding().encodeToString(selectedVariant.toByteArray(Charsets.UTF_8)))
        model.addAttribute("users", users)
        model.addAttribute("pathKey", pathKey)
        model.addAttribute("goalState", goalState)
        model.addAttribute("size", size)
        model.addAttribute("afterUser", cursor)
        model.addAttribute("allVariants", allVariants)
        model.addAttribute("navigation", navigation)
        model.addAttribute("patternListReturnTo", aggregate.location(request.contextPath, id,
            JourneyQuery(query.from, query.until, variant = selectedVariant), null, 1, FunnelSelection.REACHED))
        return "journey/pattern-users"
    }
}

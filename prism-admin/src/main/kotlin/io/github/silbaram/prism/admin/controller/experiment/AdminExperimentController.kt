package io.github.silbaram.prism.admin.controller.experiment

import io.github.silbaram.prism.admin.controller.dto.ExperimentFormDto
import io.github.silbaram.prism.admin.controller.dto.ExperimentFormView
import io.github.silbaram.prism.admin.exception.DuplicateExperimentKeyException
import io.github.silbaram.prism.admin.exception.InvalidVariantWeightException
import io.github.silbaram.prism.admin.service.AnalyticsService
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.dto.ExperimentCreateDto
import io.github.silbaram.prism.admin.service.dto.ExperimentUpdateDto
import io.github.silbaram.prism.admin.service.dto.TargetingRuleDto
import io.github.silbaram.prism.admin.service.dto.VariantDto
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.validation.BindingResult
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.bind.MethodArgumentNotValidException

@Controller
@RequestMapping("/admin/experiments")
class AdminExperimentController(
    private val experimentService: ExperimentService,
    private val analyticsService: AnalyticsService
) {

    @InitBinder
    fun validateCollectionFields(request: org.springframework.web.context.request.WebRequest) {
        // Validate before constructor binding: sparse/out-of-range list indices can throw
        // framework indexing exceptions before a BindingResult can be produced.
        for ((collection, fields, limit) in listOf(
            Triple("variants", "name|weight", 256), Triple("targetingRules", "expression", 100)
        )) {
            val pattern = Regex("$collection\\[(0|[1-9][0-9]*)]\\.($fields)")
            val indices = request.parameterMap.keys.filter { it.startsWith("$collection[") }.map { name ->
                val index = pattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
                require(index != null && index in 0 until limit) { "변형·타겟팅 입력 번호와 개수를 확인하세요." }
                index
            }.toSortedSet()
            require(indices.withIndex().all { (expected, actual) -> expected == actual }) {
                "변형·타겟팅 입력 번호는 0부터 연속되어야 합니다."
            }
        }
    }

    @ExceptionHandler(IllegalArgumentException::class, InvalidVariantWeightException::class, DuplicateExperimentKeyException::class,
        MethodArgumentNotValidException::class)
    fun invalidExperiment(exception: Exception, request: HttpServletRequest): ModelAndView {
        val message = if (exception is MethodArgumentNotValidException) "입력 형식과 필수 항목을 확인하세요."
            else exception.message ?: "실험 입력값을 확인하세요."
        if (request.method == "POST" && Regex("/admin/experiments(?:/[0-9]+)?").matches(request.servletPath)) {
            return formError(request, listOf(message))
        }
        return ModelAndView("error/status", mapOf("errorStatus" to 400, "errorMessage" to message), HttpStatus.BAD_REQUEST)
    }

    private fun formError(request: HttpServletRequest, messages: List<String>): ModelAndView {
        val id = Regex("/admin/experiments/([0-9]+)").matchEntire(request.servletPath)?.groupValues?.get(1)?.toLongOrNull()
        val entity = try { id?.let(experimentService::getExperimentById) ?: ExperimentEntity(key = "", description = "") }
        catch (_: io.github.silbaram.prism.admin.exception.ExperimentNotFoundException) {
            return ModelAndView("error/status", mapOf("errorStatus" to 404, "errorMessage" to "실험을 찾을 수 없습니다."), HttpStatus.NOT_FOUND)
        }
        val input = request.parameterMap.mapValues { it.value.firstOrNull().orEmpty() }
        return ModelAndView("experiment/form", mapOf("experiment" to formView(entity, input), "formErrors" to messages), HttpStatus.BAD_REQUEST)
    }

    private fun formView(entity: ExperimentEntity, input: Map<String, String>? = null) = ExperimentFormView.from(
        entity, entity.id?.let(experimentService::isConfigurationLocked) ?: false, input)

    private fun bindingErrors(result: BindingResult) = result.allErrors.map {
        if (it is org.springframework.validation.FieldError && it.isBindingFailure) "입력 형식을 확인하세요: ${it.field}"
        else it.defaultMessage ?: "실험 입력값을 확인하세요."
    }

    private fun parseTime(value: String): java.time.LocalDateTime? = try {
        value.takeIf { it.isNotBlank() }?.let(java.time.LocalDateTime::parse)
    } catch (_: java.time.format.DateTimeParseException) { throw IllegalArgumentException("시작·종료 시각은 UTC 날짜·시간 형식이어야 합니다.") }

    // 1. 실험 목록 페이지 (페이지네이션 및 검색 기능 포함)
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) status: ExperimentStatus?,
        model: Model
    ): String {
        require(page >= 0 && size in 1..100) { "페이지 번호는 0 이상, 페이지 크기는 1–100이어야 합니다." }
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val experiments = experimentService.searchExperiments(keyword, status, pageable)

        model.addAttribute("experiments", experiments)
        model.addAttribute("keyword", keyword ?: "")
        model.addAttribute("status", status)
        model.addAttribute("statuses", ExperimentStatus.entries)
        return "experiment/list"
    }

    // 2. 실험 생성 폼 페이지
    @GetMapping("/new")
    fun createForm(model: Model): String {
        model.addAttribute("experiment", formView(ExperimentEntity(key = "", description = "")))
        return "experiment/form"
    }

    // 3. 실험 저장 처리
    @PostMapping
    fun save(@Valid @ModelAttribute form: ExperimentFormDto, binding: BindingResult, request: HttpServletRequest): ModelAndView {
        if (binding.hasErrors()) return formError(request, bindingErrors(binding))
        require(form.status == ExperimentStatus.DRAFT) { "새 실험은 DRAFT로 생성한 뒤 수정 화면에서 시작·예약하세요." }
        val createDto = ExperimentCreateDto(
            key = form.key,
            description = form.description,
            goalEventName = form.goalEventName,
            variants = form.variants.map { VariantDto(it.name, it.weight) },
            targetingRules = form.targetingRules.map { TargetingRuleDto(it.expression) },
            trafficAllocation = form.trafficAllocation,
            startsAt = parseTime(form.startsAt), endsAt = parseTime(form.endsAt),
            guardrailEventNames = form.guardrailEvents.lines().map(String::trim).filter(String::isNotBlank).toSet(),
            layerKey = form.layerKey.takeIf { it.isNotBlank() }, layerStart = form.layerStart, layerEnd = form.layerEnd,
            stickyBucketing = form.stickyBucketing
        )
        experimentService.createExperiment(createDto)
        return ModelAndView("redirect:/admin/experiments")
    }

    // 4. 실험 수정 폼 페이지
    @GetMapping("/{id}/edit")
    fun editForm(@PathVariable id: Long, model: Model): String {
        val experiment = experimentService.getExperimentById(id)
        model.addAttribute("experiment", formView(experiment))
        return "experiment/form"
    }

    // 5. 실험 수정 처리
    @PostMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @ModelAttribute form: ExperimentFormDto,
               binding: BindingResult, request: HttpServletRequest): ModelAndView {
        if (binding.hasErrors()) return formError(request, bindingErrors(binding))
        val updateDto = ExperimentUpdateDto(
            key = form.key,
            description = form.description,
            goalEventName = form.goalEventName,
            status = form.status,
            variants = form.variants.map { VariantDto(it.name, it.weight) },
            targetingRules = form.targetingRules.map { TargetingRuleDto(it.expression) },
            trafficAllocation = form.trafficAllocation,
            startsAt = parseTime(form.startsAt), endsAt = parseTime(form.endsAt),
            guardrailEventNames = form.guardrailEvents.lines().map(String::trim).filter(String::isNotBlank).toSet(),
            layerKey = form.layerKey.takeIf { it.isNotBlank() }, layerStart = form.layerStart, layerEnd = form.layerEnd,
            stickyBucketing = form.stickyBucketing
        )
        experimentService.updateExperiment(id, updateDto)
        return ModelAndView("redirect:/admin/experiments")
    }

    // 6. 실험 삭제 처리
    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: Long): String {
        experimentService.deleteExperiment(id)
        return "redirect:/admin/experiments"
    }

    @GetMapping("/{id}/events")
    fun events(@PathVariable id: Long, model: Model): String {
        val experiment = experimentService.getExperimentById(id)
        model.addAttribute("experiment", experiment)
        model.addAttribute("events", analyticsService.getEventStats(experiment.key))
        return "experiment/events"
    }

    // 7. 실험 상세 페이지
    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long, model: Model): String {
        val experiment = experimentService.getExperimentById(id)
        val stats = analyticsService.getExperimentStats(experiment.key)

        model.addAttribute("experiment", experiment)
        model.addAttribute("stats", stats)
        model.addAttribute("changes", experimentService.getChangeHistory(id))
        return "experiment/detail"
    }
}

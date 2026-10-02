package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.controller.dto.SavedFunnelForm
import io.github.silbaram.prism.admin.controller.dto.utcDateTimeInputType
import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.exception.SavedFunnelConflictException
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.SavedFunnelService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.ModelAndView

@Controller
@RequestMapping("/admin/experiments/{id}/funnels")
class SavedFunnelController(private val experiments: ExperimentService, private val saved: SavedFunnelService) {
    @GetMapping
    fun list(@PathVariable id: Long, @RequestParam(required = false) afterId: Long?): ModelAndView {
        val page = saved.list(id, afterId)
        return ModelAndView("funnel/saved-list", mapOf("experiment" to experiments.getExperimentById(id),
            "savedFunnels" to page.items, "nextId" to page.nextId, "listAfterId" to afterId))
    }

    @GetMapping("/new")
    fun new(@PathVariable id: Long, @ModelAttribute form: SavedFunnelForm) = formPage(id, null, form, "새 퍼널 저장")

    @GetMapping("/{funnelId}/edit")
    fun edit(@PathVariable id: Long, @PathVariable funnelId: Long) =
        formPage(id, funnelId, SavedFunnelForm.from(saved.get(id, funnelId)), "퍼널 수정")

    @GetMapping("/{funnelId}/copy")
    fun copy(@PathVariable id: Long, @PathVariable funnelId: Long): ModelAndView {
        val original = SavedFunnelForm.from(saved.get(id, funnelId))
        val prefix = original.name.take(114).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        return formPage(id, null, original.copy(name = prefix + " (복사본)", version = ""), "퍼널 복제")
    }

    @PostMapping
    fun create(@PathVariable id: Long, @ModelAttribute form: SavedFunnelForm): ModelAndView =
        saveForm(id, null, form) { saved.create(id, form.input()).id }

    @PostMapping("/{funnelId}")
    fun update(@PathVariable id: Long, @PathVariable funnelId: Long, @ModelAttribute form: SavedFunnelForm): ModelAndView =
        saveForm(id, funnelId, form) { saved.update(id, funnelId, form.parsedVersion(), form.input()).id }

    @PostMapping("/{funnelId}/delete")
    fun delete(@PathVariable id: Long, @PathVariable funnelId: Long, @RequestParam version: String): ModelAndView {
        saved.delete(id, funnelId, SavedFunnelForm(version = version).parsedVersion())
        return ModelAndView("redirect:/admin/experiments/$id/funnels")
    }

    @GetMapping("/{funnelId}")
    fun run(@PathVariable id: Long, @PathVariable funnelId: Long): ModelAndView {
        val result = saved.run(id, funnelId)
        val query = result.query
        return ModelAndView("experiment/funnel", mapOf("experiment" to experiments.getExperimentById(id),
            "savedFunnel" to result.saved, "steps" to query.steps.joinToString("\n"),
            "from" to query.from.toString(), "until" to query.until.toString(),
            "fromInputType" to utcDateTimeInputType(query.from.toString()),
            "untilInputType" to utcDateTimeInputType(query.until.toString()),
            "windowHours" to query.windowHours, "report" to result.report))
    }

    private fun formPage(id: Long, funnelId: Long?, form: SavedFunnelForm, heading: String, error: String? = null,
        status: HttpStatus = HttpStatus.OK) = ModelAndView("funnel/saved-form", mapOf(
        "experiment" to experiments.getExperimentById(id), "form" to form, "editing" to (funnelId != null),
        "funnelId" to funnelId, "formHeading" to heading, "formError" to error), status)

    private fun saveForm(id: Long, funnelId: Long?, form: SavedFunnelForm, save: () -> Long): ModelAndView = try {
        ModelAndView("redirect:/admin/experiments/$id/funnels/${save()}")
    } catch (error: AdminValidationException) {
        formPage(id, funnelId, form, if (funnelId == null) "새 퍼널 저장" else "퍼널 수정", error.message, HttpStatus.BAD_REQUEST)
    } catch (error: SavedFunnelConflictException) {
        formPage(id, funnelId, form, "퍼널 수정", error.message, HttpStatus.CONFLICT)
    } catch (_: OptimisticLockingFailureException) {
        formPage(id, funnelId, form, "퍼널 수정", SavedFunnelConflictException().message, HttpStatus.CONFLICT)
    } catch (_: DataIntegrityViolationException) {
        formPage(id, funnelId, form, if (funnelId == null) "새 퍼널 저장" else "퍼널 수정",
            "다른 저장과 충돌했습니다. 퍼널 이름과 최신 설정을 확인하세요.", HttpStatus.CONFLICT)
    }
}

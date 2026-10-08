package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.service.EventCatalogService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.ModelAndView

@Controller
@RequestMapping("/admin/events")
class EventCatalogController(private val catalog: EventCatalogService) {
    @GetMapping
    fun index(@RequestParam(defaultValue = "") q: String, model: Model): String {
        model.addAllAttributes(page(q))
        return "event/catalog"
    }

    @GetMapping("/suggestions")
    @ResponseBody
    fun suggestions(@RequestParam(defaultValue = "") q: String) = catalog.suggestions(q)

    @PostMapping
    fun register(@RequestParam name: String, @RequestParam(defaultValue = "") description: String): ModelAndView = try {
        catalog.register(name, description)
        ModelAndView("redirect:/admin/events", mapOf("q" to name))
    } catch (error: AdminValidationException) {
        ModelAndView("event/catalog", page("") + mapOf("name" to name, "description" to description, "formError" to error.message), HttpStatus.BAD_REQUEST)
    }

    private fun page(query: String) = mapOf("catalog" to catalog.catalog(query), "q" to query,
        "name" to "", "description" to "")
}

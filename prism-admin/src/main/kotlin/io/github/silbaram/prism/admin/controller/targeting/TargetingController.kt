package io.github.silbaram.prism.admin.controller.targeting

import io.github.silbaram.prism.admin.service.TargetingPreviewService
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.TargetingAttributeRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.data.domain.Sort
import org.springframework.dao.DataIntegrityViolationException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.ModelAndView

data class TargetingPreviewRequest(val expressions: List<String> = emptyList(), val attributes: String = "{}")

@Controller
@RequestMapping("/admin/targeting")
class TargetingController(private val attributes: TargetingAttributeRepository, private val preview: TargetingPreviewService,
                          private val creator: TargetingAttributeCreator) {
    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("attributes", attributes.findAll(Sort.by("name")))
        return "targeting/index"
    }

    @GetMapping("/attributes") @ResponseBody
    fun catalog() = attributes.findAll(Sort.by("name"))

    @PostMapping("/attributes")
    fun register(@RequestParam name: String, @RequestParam type: TargetingAttributeType, @RequestParam(defaultValue = "") description: String): String {
        require(name.isNotBlank() && name == name.trim() && name.length <= 255 && description.length <= 1000) { "속성 이름·설명을 확인하세요." }
        require(!attributes.existsById(name)) { "이미 등록된 속성입니다." }
        // persist rather than merge prevents concurrent registration from rewriting an existing type.
        try { creator.create(name, type, description) } catch (_: DataIntegrityViolationException) { throw IllegalArgumentException("이미 등록된 속성입니다.") }
        return "redirect:/admin/targeting"
    }

    @PostMapping("/preview") @ResponseBody
    fun evaluate(@RequestBody request: TargetingPreviewRequest) = preview.evaluate(request.expressions, preview.attributes(request.attributes))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException, request: HttpServletRequest): Any {
        val message = error.message ?: "입력값을 확인하세요."
        return if (request.servletPath.endsWith("/preview")) ResponseEntity.badRequest().body(mapOf("message" to message))
        else ModelAndView("error/status", mapOf("errorStatus" to 400, "errorMessage" to message), HttpStatus.BAD_REQUEST)
    }
}

@org.springframework.stereotype.Service
class TargetingAttributeCreator(private val entityManager: jakarta.persistence.EntityManager) {
    @org.springframework.transaction.annotation.Transactional
    fun create(name: String, type: TargetingAttributeType, description: String) {
        entityManager.persist(TargetingAttributeEntity(name, type, description))
        entityManager.flush()
    }
}

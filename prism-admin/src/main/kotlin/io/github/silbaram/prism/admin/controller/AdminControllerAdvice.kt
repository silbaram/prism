package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.ModelAndView
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@ControllerAdvice
class AdminControllerAdvice {
    @ModelAttribute("canManageExperiments") fun canManage(): Boolean =
        SecurityContextHolder.getContext().authentication?.authorities?.any { it.authority == "ROLE_ADMIN" } == true
    @ModelAttribute("currentUser") fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name.orEmpty()

    @ExceptionHandler(ExperimentNotFoundException::class)
    fun missing(error: ExperimentNotFoundException) = message(404, error.message.orEmpty())

    @ExceptionHandler(MethodArgumentNotValidException::class, MethodArgumentTypeMismatchException::class)
    fun invalid() = message(400, "입력 형식과 필수 항목을 확인하세요.")

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidValue() = message(400, "입력값을 확인하세요.")

    @ExceptionHandler(io.github.silbaram.prism.admin.exception.AdminValidationException::class)
    fun invalidDomainValue(error: io.github.silbaram.prism.admin.exception.AdminValidationException) =
        message(400, error.message.orEmpty())

    @ExceptionHandler(DataIntegrityViolationException::class, PessimisticLockingFailureException::class)
    fun conflict() = message(409, "다른 변경과 충돌했습니다. 최신 실험을 다시 확인하세요.")

    private fun message(status: Int, body: String) = ModelAndView("error/status",
        mapOf("errorStatus" to status, "errorMessage" to body), HttpStatus.valueOf(status))
}

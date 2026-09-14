package io.github.silbaram.prism.admin.exception

/** Only deliberate, user-facing validation messages may cross the controller boundary. */
class AdminValidationException(message: String) : IllegalArgumentException(message)

internal inline fun requireValidInput(value: Boolean, message: () -> String = { "입력값을 확인하세요." }) {
    if (!value) throw AdminValidationException(message())
}

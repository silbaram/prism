package io.github.silbaram.prism.common.rest

enum class ResponseCode(val code: String, val message: String) {
    SUCCESS("0000", "Success"),
    GENERAL_ERROR("9999", "General error"),
    // A/B test not found or not active (9000 ~ 9100)
    EXPERIMENT_NOT_FOUND("9000", "Experiment not found or not active");
}

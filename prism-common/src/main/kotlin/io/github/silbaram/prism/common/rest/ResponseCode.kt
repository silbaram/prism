package io.github.silbaram.prism.common.rest

enum class ResponseCode(val code: String, val message: String) {
    SUCCESS("0000", "Success"),
    IMPRESSION_NOT_FOUND("9100", "No prior impression for this user and experiment"),
    // A/B test not found or not active (9000 ~ 9100)
    EXPERIMENT_NOT_FOUND("9000", "Experiment not found or not active");
}

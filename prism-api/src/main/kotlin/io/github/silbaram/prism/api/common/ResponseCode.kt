package io.github.silbaram.prism.api.common

enum class ResponseCode(val code: String, val message: String) {
    SUCCESS("0000", "Success"),
    //TABLE에 대이터 없음 예외 (9000 ~ 9100)
    EXPERIMENT_NOT_FOUND("9000", "Experiment not found or not active");
}

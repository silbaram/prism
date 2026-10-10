package io.github.silbaram.prism.sdk

/** Local SDK failures; these are not server response codes. */
enum class SdkResponseCode(val code: String) {
    CLIENT_ERROR("9999"),
    EXPOSURE_DEDUP_CAPACITY_REACHED("9998"),
    TARGETING_CONFIGURATION_REJECTED("9997")
}

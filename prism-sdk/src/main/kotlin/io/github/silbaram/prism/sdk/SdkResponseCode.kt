package io.github.silbaram.prism.sdk

/** Local transport failures; these are not server response codes. */
enum class SdkResponseCode(val code: String) {
    CLIENT_ERROR("9999")
}

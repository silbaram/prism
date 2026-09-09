package io.github.silbaram.prism.starter.service

import io.github.silbaram.prism.sdk.PrismExperimentClient

/** Tracks only previously exposed users, including across method, thread and request boundaries. */
class PrismConversionTracker(private val prismExperimentClient: PrismExperimentClient) {
    /** Returns server acceptance. A missing exposure skips tracking without assigning the user. */
    fun trackConversionSafe(userId: String, experimentKey: String, eventName: String): Boolean =
        prismExperimentClient.trackIfAssigned(userId, experimentKey, eventName)
}

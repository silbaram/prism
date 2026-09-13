package io.github.silbaram.prism.starter.service

import io.github.silbaram.prism.sdk.PrismExperimentClient

/** Tracks only previously exposed users, including across method, thread and request boundaries. */
class PrismConversionTracker(private val prismExperimentClient: PrismExperimentClient) {
    /** Returns local queue acceptance in LOCAL mode, server acceptance in REMOTE mode. Never assigns during tracking. */
    fun trackConversionSafe(userId: String, experimentKey: String, eventName: String): Boolean =
        prismExperimentClient.trackIfAssigned(userId, experimentKey, eventName)
}

package io.github.silbaram.prism.starter.annotation

/** Records an event after execution for a user with a prior exposure in this experiment.
 * Works independently of @PrismExperiment and across requests. Requires exactly one @PrismUserId.
 * Only the experiment's configured goalEventName contributes to CVR; other events are auxiliary.
 * Repeated events are retained, but each user counts once per variant in CVR.
 *
 * Example (configure the experiment goal as "purchase"):
 * ```kotlin
 * @PrismTrackConversion(experimentKey = "checkout", eventName = "purchase", trackWhen = TrackCondition.RETURN_TRUE)
 * fun purchase(@PrismUserId userId: String): Boolean = completePayment(userId)
 * ```
 *
 * @property trackOnException Records on exceptions only with ALWAYS, since exceptions have no return value.
 * Use a separate auxiliary event name for failures so they do not count toward a success goal.
 */
@JvmRepeatable(PrismTrackConversions::class)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrismTrackConversion(
    val experimentKey: String,
    val eventName: String,
    val trackOnException: Boolean = false,
    val trackWhen: TrackCondition = TrackCondition.ALWAYS
)

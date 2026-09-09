package io.github.silbaram.prism.starter.annotation

/** Records experiment exposure before this method executes.
 * Exactly one parameter must be marked with @PrismUserId.
 * Use PrismExperimentClient.assign() or PrismStrategyResolver when the variant determines behavior.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrismExperiment(val experimentKey: String)

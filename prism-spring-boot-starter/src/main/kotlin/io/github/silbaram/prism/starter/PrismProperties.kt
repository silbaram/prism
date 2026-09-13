package io.github.silbaram.prism.starter

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import io.github.silbaram.prism.sdk.EvaluationMode

@ConfigurationProperties(prefix = "prism.client")
data class PrismProperties(
    var url: String = "",
    var timeout: Duration = Duration.ofSeconds(5),
    var assignmentCacheTtl: Duration = Duration.ofSeconds(30),
    var assignmentCacheMaximumSize: Long = 10_000,
    var evaluationMode: EvaluationMode = EvaluationMode.LOCAL,
    var configSyncInterval: Duration = Duration.ofSeconds(60),
    var initializationTimeout: Duration = Duration.ofSeconds(5),
    var eventFlushInterval: Duration = Duration.ofSeconds(5),
    var eventBatchSize: Int = 100,
    var eventQueueCapacity: Int = 10_000,
    var exposureCacheMaximumSize: Long = 10_000,
    var shutdownTimeout: Duration = Duration.ofSeconds(5)
)

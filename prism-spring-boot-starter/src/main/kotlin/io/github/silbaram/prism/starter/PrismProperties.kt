package io.github.silbaram.prism.starter

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "prism.client")
data class PrismProperties(
    var url: String = "",
    var timeout: Duration = Duration.ofSeconds(5),
    var assignmentCacheTtl: Duration = Duration.ofSeconds(30),
    var assignmentCacheMaximumSize: Long = 10_000
)

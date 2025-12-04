package io.github.silbaram.prism.starter

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "prism.client")
data class PrismProperties(
    var url: String = "",
    var timeout: Duration = Duration.ofSeconds(5)
)

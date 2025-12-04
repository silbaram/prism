package io.github.silbaram.prism.starter

import io.github.silbaram.prism.sdk.PrismClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnClass(PrismClient::class)
@EnableConfigurationProperties(PrismProperties::class)
class PrismAutoConfiguration(
    private val properties: PrismProperties
) {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "prism.client", name = ["url"])
    fun prismClient(): PrismClient {
        return PrismClient(
            baseUrl = properties.url,
            timeout = properties.timeout
        )
    }
}

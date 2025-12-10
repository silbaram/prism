package io.github.silbaram.prism.starter

import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.starter.aop.PrismExperimentAspect
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy

@Configuration
@ConditionalOnClass(PrismClient::class)
@EnableConfigurationProperties(PrismProperties::class)
@EnableAspectJAutoProxy
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

    @Bean
    @ConditionalOnBean(PrismClient::class)
    @ConditionalOnMissingBean
    fun prismExperimentAspect(prismClient: PrismClient): PrismExperimentAspect {
        return PrismExperimentAspect(prismClient)
    }
}

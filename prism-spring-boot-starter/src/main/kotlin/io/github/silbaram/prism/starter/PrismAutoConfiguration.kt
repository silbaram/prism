package io.github.silbaram.prism.starter

import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.aop.PrismExperimentAspect
import io.github.silbaram.prism.starter.service.PrismConversionTracker
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
    fun prismExperimentClient(): PrismExperimentClient {
        val prismClient = PrismClient(
            baseUrl = properties.url,
            timeout = properties.timeout
        )
        return PrismExperimentClient(prismClient)
    }

    @Bean
    @ConditionalOnBean(PrismExperimentClient::class)
    @ConditionalOnMissingBean
    fun prismExperimentAspect(prismExperimentClient: PrismExperimentClient): PrismExperimentAspect {
        return PrismExperimentAspect(prismExperimentClient)
    }

    @Bean
    @ConditionalOnBean(PrismExperimentClient::class)
    @ConditionalOnMissingBean
    fun prismConversionTracker(prismExperimentClient: PrismExperimentClient): PrismConversionTracker {
        return PrismConversionTracker(prismExperimentClient)
    }
}

package io.github.silbaram.prism.starter

import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.aop.PrismExperimentAspect
import io.github.silbaram.prism.starter.aop.PrismTrackConversionAspect
import io.github.silbaram.prism.starter.service.PrismConversionTracker
import io.github.silbaram.prism.starter.strategy.PrismStrategyResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
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
    fun prismExperimentClient(prismClient: PrismClient): PrismExperimentClient {
        return PrismExperimentClient(prismClient, properties.assignmentCacheTtl, properties.assignmentCacheMaximumSize)
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

    @Bean
    @ConditionalOnBean(PrismExperimentClient::class)
    @ConditionalOnMissingBean
    fun prismTrackConversionAspect(prismExperimentClient: PrismExperimentClient): PrismTrackConversionAspect {
        return PrismTrackConversionAspect(prismExperimentClient)
    }

    @Bean
    @ConditionalOnBean(PrismExperimentClient::class)
    @ConditionalOnMissingBean
    fun prismStrategyResolver(
        applicationContext: ApplicationContext,
        prismExperimentClient: PrismExperimentClient
    ): PrismStrategyResolver {
        return PrismStrategyResolver(applicationContext, prismExperimentClient)
    }
}

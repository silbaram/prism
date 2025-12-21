package io.github.silbaram.prism.starter

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.aop.PrismExperimentAspect
import io.github.silbaram.prism.starter.service.PrismConversionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PrismAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))

    @Test
    fun `should create PrismExperimentClient and related beans when url is configured`() {
        contextRunner
            .withPropertyValues("prism.client.url=http://localhost:8080")
            .run { context ->
                assertThat(context).hasSingleBean(PrismExperimentClient::class.java)
                assertThat(context).hasSingleBean(PrismExperimentAspect::class.java)
                assertThat(context).hasSingleBean(PrismConversionTracker::class.java)
            }
    }

    @Test
    fun `should not create beans when url is missing`() {
        contextRunner
            .run { context ->
                assertThat(context).doesNotHaveBean(PrismExperimentClient::class.java)
                assertThat(context).doesNotHaveBean(PrismExperimentAspect::class.java)
                assertThat(context).doesNotHaveBean(PrismConversionTracker::class.java)
            }
    }
}

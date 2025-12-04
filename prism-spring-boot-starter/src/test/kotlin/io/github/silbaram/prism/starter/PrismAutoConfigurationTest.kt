package io.github.silbaram.prism.starter

import io.github.silbaram.prism.sdk.PrismClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PrismAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))

    @Test
    fun `should create PrismClient bean when url is configured`() {
        contextRunner
            .withPropertyValues("prism.client.url=http://localhost:8080")
            .run { context ->
                assertThat(context).hasSingleBean(PrismClient::class.java)
            }
    }

    @Test
    fun `should not create PrismClient bean when url is missing`() {
        contextRunner
            .run { context ->
                assertThat(context).doesNotHaveBean(PrismClient::class.java)
            }
    }
}

package io.github.silbaram.prism.api.event

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import tools.jackson.databind.DeserializationFeature

@Configuration
class EventJsonConfiguration {
    /** Preserve future numeric metadata instead of rounding it through a Double during HTTP admission. */
    @Bean fun eventNumbers() = JsonMapperBuilderCustomizer { it.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS) }
}

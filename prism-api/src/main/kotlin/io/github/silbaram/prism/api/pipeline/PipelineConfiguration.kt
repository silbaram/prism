package io.github.silbaram.prism.api.pipeline

import org.springframework.boot.context.properties.*
import org.springframework.context.annotation.Configuration

enum class PipelineMode { DIRECT, KAFKA }
@ConfigurationProperties("prism.pipeline")
data class PipelineProperties(
    var mode: PipelineMode = PipelineMode.DIRECT,
    var bootstrapServers: String = "",
    var topic: String = "prism.events.v1",
    var warehouseTopic: String = "prism.warehouse.v1",
    var deadLetterTopic: String = "prism.dead-letter.v1",
    var groupId: String = "prism-materializer-v1",
    var consumerEnabled: Boolean = true,
    var maxOutboxRows: Long = 100_000,
    var clientProperties: Map<String, String> = emptyMap()
) {
    override fun toString() = "PipelineProperties(mode=$mode, clientProperties=<redacted>)"
}
@Configuration @EnableConfigurationProperties(PipelineProperties::class)
class PipelineConfiguration

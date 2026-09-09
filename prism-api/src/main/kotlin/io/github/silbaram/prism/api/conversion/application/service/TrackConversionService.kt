package io.github.silbaram.prism.api.conversion.application.service

import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionCommand
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionResult
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionUseCase
import io.github.silbaram.prism.api.conversion.application.port.out.LoadImpressionPort
import io.github.silbaram.prism.api.conversion.application.port.out.RecordConversionPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Records events only for a previously exposed user, attributed to their latest exposure. */
@Service
class TrackConversionService(
    private val loadImpressionPort: LoadImpressionPort,
    private val recordConversionPort: RecordConversionPort
) : TrackConversionUseCase {
    @Transactional
    override fun trackConversion(command: TrackConversionCommand): TrackConversionResult {
        val impression = loadImpressionPort.loadLatestImpression(command.userId, command.experimentKey)
            ?: return TrackConversionResult.Rejected(command)
        recordConversionPort.recordConversion(command.experimentKey, command.userId,
            command.eventName, impression)
        return TrackConversionResult.Recorded(command, impression.variantName)
    }
}

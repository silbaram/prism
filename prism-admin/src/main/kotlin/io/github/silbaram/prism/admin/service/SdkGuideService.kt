package io.github.silbaram.prism.admin.service

import org.springframework.stereotype.Service

data class SdkCodeExamples(val java: String, val kotlin: String)

@Service
class SdkGuideService {
    fun examples(experimentKey: String, eventName: String): SdkCodeExamples {
        val javaKey = literal(experimentKey, kotlin = false)
        val javaEvent = literal(eventName, kotlin = false)
        val kotlinKey = literal(experimentKey, kotlin = true)
        val kotlinEvent = literal(eventName, kotlin = true)
        return SdkCodeExamples("""
            import io.github.silbaram.prism.sdk.AssignmentOutcome;
            import io.github.silbaram.prism.sdk.PrismExperimentClient;
            import org.springframework.stereotype.Service;
            import java.util.Map;

            @Service
            public class ExperimentTracking {
                private final PrismExperimentClient experiments;

                public ExperimentTracking(PrismExperimentClient experiments) {
                    this.experiments = experiments;
                }

                // 실험 경험을 실제 제공하는 시점에 호출합니다.
                public AssignmentOutcome expose(String userId, Map<String, Object> attributes) {
                    return experiments.assign(userId, $javaKey, attributes);
                }

                // 업무 처리 성공과 트랜잭션 커밋 후 호출합니다.
                public boolean recordEvent(String userId) {
                    return experiments.trackIfAssigned(userId, $javaKey, $javaEvent);
                }
            }
        """.trimIndent(), """
            import io.github.silbaram.prism.sdk.AssignmentOutcome
            import io.github.silbaram.prism.sdk.PrismExperimentClient
            import org.springframework.stereotype.Service

            @Service
            class ExperimentTracking(private val experiments: PrismExperimentClient) {
                // 실험 경험을 실제 제공하는 시점에 호출합니다.
                fun expose(userId: String, attributes: Map<String, Any> = emptyMap()): AssignmentOutcome =
                    experiments.assign(userId, $kotlinKey, attributes)

                // 업무 처리 성공과 트랜잭션 커밋 후 호출합니다.
                fun recordEvent(userId: String): Boolean =
                    experiments.trackIfAssigned(userId, $kotlinKey, $kotlinEvent)
            }
        """.trimIndent())
    }

    // HTML escaping alone cannot prevent a copied value from becoming executable source code.
    private fun literal(value: String, kotlin: Boolean): String = buildString {
        append('"')
        value.forEach { char ->
            append(when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                '\b' -> "\\b"
                '$' -> if (kotlin) "\\$" else "$"
                else -> if (char.code < 32 || char.code == 127) {
                    if (kotlin) "\\u%04x".format(char.code) else "\\%03o".format(char.code)
                } else char.toString()
            })
        }
        append('"')
    }
}

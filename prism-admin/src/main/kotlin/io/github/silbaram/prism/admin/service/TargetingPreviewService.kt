package io.github.silbaram.prism.admin.service

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.silbaram.prism.core.targeting.*
import org.springframework.stereotype.Service

data class TargetingPreview(val matched: Boolean, val rules: List<Boolean>)

@Service
class TargetingPreviewService {
    private val mapper = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxNumberLength(256).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun attributes(json: String): UserContext {
        require(json.length <= 16_000) { "샘플 속성은 16,000자 이하여야 합니다." }
        val node = try { mapper.readTree(json) } catch (_: Exception) { throw IllegalArgumentException("샘플 속성의 JSON 형식을 확인하세요.") }
        require(node != null && node.isObject && node.size() <= 100) { "샘플 속성은 최대 100개 필드의 JSON 객체여야 합니다." }
        val values = node.properties().mapNotNull { (name, value) ->
            require(name.isNotBlank() && name.length <= 255) { "속성 이름을 확인하세요." }
            val scalar: Any? = when {
                value.isNull -> null
                value.isTextual -> value.textValue()
                value.isBoolean -> value.booleanValue()
                value.isNumber -> {
                    val decimal = value.decimalValue()
                    require(decimal.precision() <= 256 && decimal.scale() in -1024..1024) { "숫자 범위를 확인하세요." }
                    // SpEL arithmetic depends on the JVM number type. Keep JSON integers integral
                    // while preserving exact decimal samples for the portable evaluator.
                    when {
                        !value.isIntegralNumber -> decimal
                        value.canConvertToInt() -> value.intValue()
                        value.canConvertToLong() -> value.longValue()
                        else -> value.bigIntegerValue()
                    }
                }
                else -> throw IllegalArgumentException("속성 값에는 문자열·숫자·불리언·null만 사용할 수 있습니다.")
            }
            scalar?.let { name to it }
        }.toMap()
        return UserContext(values)
    }

    fun evaluate(expressions: List<String>, context: UserContext): TargetingPreview {
        require(expressions.size <= 100) { "타기팅 규칙은 최대 100개입니다." }
        // Validate all rules, including branches which would otherwise be short-circuited.
        expressions.forEach(RuleEvaluator::validateSyntax)
        val results = expressions.map { RuleEvaluator.evaluate(TargetingRule(it), context) }
        return TargetingPreview(results.all { it }, results)
    }
}

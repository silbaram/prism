package io.github.silbaram.prism.starter.util

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 클래스에 로거를 쉽게 추가할 수 있도록 하는 extension property입니다.
 *
 * 사용 예시:
 * ```kotlin
 * class MyService {
 *     private val logger = logger()
 *
 *     fun doSomething() {
 *         logger.debug { "Doing something" }
 *     }
 * }
 * ```
 */
inline fun <reified T : Any> T.logger(): KLogger {
    return KotlinLogging.logger(T::class.java.name)
}

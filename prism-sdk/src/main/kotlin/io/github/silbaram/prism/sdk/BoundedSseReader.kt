package io.github.silbaram.prism.sdk

import java.io.IOException
import java.io.Reader

/** Unlike BufferedReader.readLine, enforces the limit before allocating an entire remote line. */
internal class BoundedSseReader(private val reader: Reader, private val maximumLength: Int) {
    private var skipLf = false
    init { require(maximumLength > 0) }

    fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val character = reader.read()
            if (character == -1) return line.takeIf { it.isNotEmpty() }?.toString()
            if (skipLf) {
                skipLf = false
                if (character == '\n'.code) continue
            }
            when (character) {
                '\n'.code -> return line.toString()
                '\r'.code -> { skipLf = true; return line.toString() }
                else -> {
                    if (line.length == maximumLength) throw IOException("Configuration stream line too large")
                    line.append(character.toChar())
                }
            }
        }
    }
}

package io.github.silbaram.prism.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.Reader
import java.io.StringReader

class BoundedSseReaderTest {
    @Test fun `SSE handles all line endings and final unterminated content`() {
        val reader = BoundedSseReader(StringReader("event: config\r\ndata: {}\r\r: heartbeat\n\nlast"), 64)
        assertEquals(listOf("event: config", "data: {}", "", ": heartbeat", "", "last"),
            generateSequence { reader.readLine() }.toList())
    }

    @Test fun `unterminated remote lines are bounded while reading`() {
        var consumed = 0
        val infinite = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                consumed++
                buffer[offset] = 'x'
                return 1
            }
            override fun close() = Unit
        }
        assertThrows(IOException::class.java) { BoundedSseReader(infinite, 32).readLine() }
        assertEquals(33, consumed)
    }
}

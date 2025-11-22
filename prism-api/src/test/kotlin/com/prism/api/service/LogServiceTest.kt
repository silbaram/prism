package com.prism.api.service

import com.prism.api.repository.ConversionRepository
import com.prism.api.repository.ImpressionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.TimeUnit
import org.awaitility.Awaitility.await

@SpringBootTest
class LogServiceTest {

    @Autowired
    lateinit var logService: LogService

    @Autowired
    lateinit var impressionRepository: ImpressionRepository

    @Autowired
    lateinit var conversionRepository: ConversionRepository

    @Test
    fun `should save impression log asynchronously`() {
        val countBefore = impressionRepository.count()
        
        logService.logImpression("test-exp", "A", "user-1")
        
        // Wait for async execution
        await().atMost(2, TimeUnit.SECONDS).until {
            impressionRepository.count() == countBefore + 1
        }
        
        val logs = impressionRepository.findAll()
        val lastLog = logs.last()
        assertEquals("test-exp", lastLog.experimentKey)
        assertEquals("A", lastLog.variant)
        assertEquals("user-1", lastLog.userId)
    }

    @Test
    fun `should save conversion log asynchronously`() {
        val countBefore = conversionRepository.count()
        
        logService.logConversion("test-exp", "user-1", "purchase")
        
        await().atMost(2, TimeUnit.SECONDS).until {
            conversionRepository.count() == countBefore + 1
        }
        
        val logs = conversionRepository.findAll()
        val lastLog = logs.last()
        assertEquals("purchase", lastLog.eventName)
    }
}

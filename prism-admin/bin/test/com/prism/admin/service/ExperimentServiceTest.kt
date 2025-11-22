
package com.prism.admin.service

import com.prism.admin.domain.ExperimentStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ExperimentServiceTest {

    @Autowired
    lateinit var experimentService: ExperimentService

    @Test
    fun `should create and retrieve experiment`() {
        val variants = listOf(
            VariantDto("A", 50),
            VariantDto("B", 50)
        )
        
        val created = experimentService.createExperiment("test-exp", "Test Description", variants)
        
        assertNotNull(created.id)
        assertEquals("test-exp", created.key)
        assertEquals(ExperimentStatus.DRAFT, created.status)
        assertEquals(2, created.variants.size)
        
        val retrieved = experimentService.getAllExperiments().find { it.key == "test-exp" }
        assertNotNull(retrieved)
        assertEquals("test-exp", retrieved?.key)
    }

    @Test
    fun `should fail if total weight is not 100`() {
        val variants = listOf(
            VariantDto("A", 30),
            VariantDto("B", 30)
        )
        
        assertThrows(IllegalArgumentException::class.java) {
            experimentService.createExperiment("invalid-weight", "Desc", variants)
        }
    }
}

package io.github.silbaram.prism.api.traffic.application.service

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.StickyAssignmentEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.StickyAssignmentRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.*
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest

@Service
class StickyAssignmentService(manager: PlatformTransactionManager, private val entityManager: EntityManager,
                              private val assignments: StickyAssignmentRepository) {
    private val transaction = TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    fun choose(experimentKey: String, userId: String, proposed: String): String {
        val id = MessageDigest.getInstance("SHA-256").digest("${experimentKey.length}:$experimentKey:$userId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun existing() = assignments.findById(id).orElse(null)?.also {
            check(it.experimentKey == experimentKey && it.userId == userId) { "Sticky identity mismatch" }
        }?.variant
        return try {
            requireNotNull(transaction.execute {
                existing() ?: proposed.also {
                    entityManager.persist(StickyAssignmentEntity(id, experimentKey, userId, proposed))
                    entityManager.flush()
                }
            })
        } catch (error: RuntimeException) {
            transaction.execute { existing() } ?: throw error
        }
    }
}

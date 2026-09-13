package io.github.silbaram.prism.infrastructure.persistence.jpa.config

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.PopulationPolicyEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.PopulationPolicyRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

@Component
class PopulationInitializer(private val policies: PopulationPolicyRepository, private val entityManager: jakarta.persistence.EntityManager,
                            manager: org.springframework.transaction.PlatformTransactionManager) : ApplicationRunner {
    private val transaction = org.springframework.transaction.support.TransactionTemplate(manager)
    override fun run(args: ApplicationArguments) {
        if (!policies.existsById(1)) {
            try { transaction.executeWithoutResult { entityManager.persist(PopulationPolicyEntity()); entityManager.flush() } }
            catch (error: RuntimeException) { if (!policies.existsById(1)) throw error }
        }
    }
}

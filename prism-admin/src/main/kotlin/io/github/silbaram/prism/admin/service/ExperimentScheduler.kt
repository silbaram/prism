package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
@EnableScheduling
class ExperimentScheduler(private val experiments: ExperimentRepository, private val service: ExperimentService,
                          @param:Value("\${prism.schedule.enabled:true}") private val enabled: Boolean) {
    private val logger = LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${prism.schedule.interval-ms:5000}")
    fun tick() {
        if (!enabled) return
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val ids = experiments.findScheduleCandidates(now)
        ids.forEach { id ->
            try { service.advanceSchedule(id, now) }
            catch (exception: RuntimeException) { logger.warn("Scheduled transition failed: experiment={}, error={}", id, exception.javaClass.simpleName) }
        }
    }
}

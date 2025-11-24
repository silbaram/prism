package io.github.silbaram.prism.admin.page.repository

import io.github.silbaram.prism.admin.page.model.ExperimentEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ExperimentRepository : JpaRepository<ExperimentEntity, Long>

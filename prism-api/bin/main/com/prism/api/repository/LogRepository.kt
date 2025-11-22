package com.prism.api.repository

import com.prism.api.domain.ConversionEntity
import com.prism.api.domain.ImpressionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ImpressionRepository : JpaRepository<ImpressionEntity, Long>

@Repository
interface ConversionRepository : JpaRepository<ConversionEntity, Long>

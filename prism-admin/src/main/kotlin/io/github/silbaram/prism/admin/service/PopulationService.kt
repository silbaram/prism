package io.github.silbaram.prism.admin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.core.model.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import io.github.silbaram.prism.admin.exception.requireValidInput as require

@Service @Transactional
class PopulationService(private val policies: PopulationPolicyRepository, private val layers: ExperimentLayerRepository,
                        private val experiments: ExperimentRepository, private val changes: ExperimentChangeRepository) {
    fun lock() = requireNotNull(policies.lockPolicy()) { "Population policy is not initialized" }

    fun validateLayer(id: Long?, key: String?, start: Int?, end: Int?) {
        if (key == null) {
            require(start == null && end == null) { "레이어 없이 버킷 범위를 지정할 수 없습니다." }
            return
        }
        require(start != null) { "레이어 시작 버킷을 지정하세요." }
        require(end != null) { "레이어 종료 버킷을 지정하세요." }
        require(key.isNotBlank() && key.length <= 255) { "레이어 키는 1–255자로 입력하세요." }
        require(start!! in 0 until end!! && end <= 10_000) { "레이어 범위는 0 ≤ 시작 < 종료 ≤ 10000이어야 합니다." }
        val range = LayerAllocation(key, start, end)
        require(layers.existsById(key)) { "등록된 레이어를 선택하세요." }
        require(experiments.findAllByLayerKey(key).none {
            it.id != id && requireNotNull(it.layerStart) < range.end && requireNotNull(it.layerEnd) > range.start
        }) { "같은 레이어의 버킷 범위는 겹칠 수 없습니다. 종료한 실험의 범위도 재사용하지 않습니다." }
    }

    @Transactional(readOnly = true) fun policy() = policies.findById(1).orElseThrow()
    @Transactional(readOnly = true) fun layers() = layers.findAll().sortedBy { it.key }
    @Transactional(readOnly = true) fun history() = changes.findTop50ByExperimentIdOrderByIdDesc(0)

    fun configureHoldout(key: String, basisPoints: Int) {
        require(key.isNotBlank() && key.length <= 255) { "홀드아웃 키는 1–255자로 입력하세요." }
        require(basisPoints in 0..10_000) { "홀드아웃 비율은 0–10000 basis points로 입력하세요." }
        require(key == key.trim()) { "홀드아웃 키의 앞뒤 공백을 제거하세요." }
        val policy = lock()
        require(policy.holdoutBasisPoints == null) { "영구 홀드아웃은 설정 후 변경할 수 없습니다." }
        policy.holdoutKey = key
        policy.holdoutBasisPoints = basisPoints
        audit("HOLDOUT", mapOf("key" to key, "basisPoints" to basisPoints))
    }

    fun createLayer(key: String, description: String) {
        require(key.isNotBlank() && key.length <= 255 && key == key.trim() && description.length <= 255)
        lock()
        require(!layers.existsById(key)) { "이미 존재하는 레이어입니다." }
        layers.save(ExperimentLayerEntity(key, description))
        audit("CREATE_LAYER", mapOf("key" to key, "description" to description))
    }

    private fun audit(action: String, values: Map<String, Any>) {
        changes.save(ExperimentChangeEntity(experimentId = 0, experimentKey = "@population", action = action,
            beforeSnapshot = null, afterSnapshot = jacksonObjectMapper().writeValueAsString(values),
            actor = SecurityContextHolder.getContext().authentication?.name ?: "system"))
    }
}

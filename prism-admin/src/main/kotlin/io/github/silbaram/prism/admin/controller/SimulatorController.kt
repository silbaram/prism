package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.splitter.TrafficSplitter
import io.github.silbaram.prism.core.targeting.UserContext
import io.github.silbaram.prism.infrastructure.persistence.repository.ExperimentRepository
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
@RequestMapping("/admin/simulator")
class SimulatorController(
    private val experimentRepository: ExperimentRepository
) {

    @GetMapping
    fun index(model: Model): String {
        val experiments = experimentRepository.findAll()
        model.addAttribute("experiments", experiments)
        return "simulator/index"
    }

    @PostMapping("/test")
    @Transactional(readOnly = true)
    fun test(
        @RequestParam experimentId: Long,
        @RequestParam userId: String,
        model: Model
    ): String {
        // 선택된 실험만 조회 (variants 포함)
        val experimentEntity = experimentRepository.findById(experimentId)
            .orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다. ID: $experimentId") }

        // ExperimentEntity를 core의 Experiment 모델로 변환
        val experiment = Experiment(
            key = experimentEntity.key,
            variants = experimentEntity.variants.map { Variant(it.name, it.weight) },
            targetingRules = emptyList()
        )

        // TrafficSplitter를 사용해 그룹 배정
        val assignedVariant = TrafficSplitter.assign(experiment, userId, UserContext(emptyMap()))

        // 드롭다운용 실험 목록은 마지막에 조회
        val experiments = experimentRepository.findAll()
        model.addAttribute("experiments", experiments)
        model.addAttribute("selectedExperimentId", experimentId)
        model.addAttribute("userId", userId)
        model.addAttribute("result", assignedVariant?.name)

        return "simulator/index"
    }
}

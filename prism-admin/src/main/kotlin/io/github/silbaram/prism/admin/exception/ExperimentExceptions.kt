package io.github.silbaram.prism.admin.exception

class ExperimentNotFoundException(id: Long) : RuntimeException("실험을 찾을 수 없습니다. ID: $id")

class DuplicateExperimentKeyException(key: String) : RuntimeException("Experiment with key $key already exists")

class InvalidVariantWeightException(totalWeight: Long) : RuntimeException("각 가중치는 0–100이어야 하고 합계는 100이어야 합니다. 현재 합계: $totalWeight")

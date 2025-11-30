package io.github.silbaram.prism.admin.exception

class ExperimentNotFoundException(id: Long) : RuntimeException("실험을 찾을 수 없습니다. ID: $id")

class DuplicateExperimentKeyException(key: String) : RuntimeException("Experiment with key $key already exists")

class InvalidVariantWeightException(totalWeight: Int) : RuntimeException("Total weight must be 100, but was $totalWeight")

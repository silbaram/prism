package io.github.silbaram.prism.api.traffic.application.port.out

import io.github.silbaram.prism.core.model.Experiment

interface LoadExperimentPort {
    fun loadExperiment(experimentKey: String): Experiment?
}

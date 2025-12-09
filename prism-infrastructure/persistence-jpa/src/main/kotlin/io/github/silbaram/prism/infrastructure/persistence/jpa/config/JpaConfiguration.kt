package io.github.silbaram.prism.infrastructure.persistence.jpa.config

import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EnableJpaRepositories(basePackages = ["io.github.silbaram.prism.infrastructure.persistence.jpa.repository"])
@EntityScan(basePackages = ["io.github.silbaram.prism.infrastructure.persistence.jpa.entities"])
class JpaConfiguration

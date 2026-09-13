package io.github.silbaram.prism.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["io.github.silbaram.prism.api", "io.github.silbaram.prism.infrastructure"])
class PrismApiApplication

fun main(args: Array<String>) {
    runApplication<PrismApiApplication>(*args)
}

package com.prism.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["io.github.silbaram.prism"])
class PrismApiApplication

fun main(args: Array<String>) {
    runApplication<PrismApiApplication>(*args)
}

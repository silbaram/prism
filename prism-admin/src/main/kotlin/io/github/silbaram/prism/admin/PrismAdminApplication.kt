package io.github.silbaram.prism.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["io.github.silbaram.prism"])
class PrismAdminApplication

fun main(args: Array<String>) {
    runApplication<PrismAdminApplication>(*args)
}

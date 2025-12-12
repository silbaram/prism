plugins {
    id("org.springframework.boot")
    `java-library`
}

tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
}

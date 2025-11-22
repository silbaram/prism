plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.springframework:spring-expression")
    implementation("org.springframework:spring-context")
}

tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
}

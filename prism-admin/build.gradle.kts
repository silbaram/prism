import org.gradle.kotlin.dsl.getByName
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.springframework.boot")
}

tasks.getByName<BootJar>("bootJar") {
    enabled = true
    archiveFileName.set("prism-admin.jar")
}

dependencies {
    implementation(project(":prism-core"))
    implementation(project(":prism-infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}

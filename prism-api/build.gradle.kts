plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":prism-core"))
    implementation(project(":prism-common"))
    implementation(project(":prism-infrastructure:persistence-jpa"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("com.github.ben-manes.caffeine:caffeine")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

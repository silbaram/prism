plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":prism-core"))
    implementation(project(":prism-infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("com.github.ben-manes.caffeine:caffeine")

    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

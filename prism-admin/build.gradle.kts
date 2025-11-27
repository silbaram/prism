
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":prism-core"))
    implementation(project(":prism-infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    
    runtimeOnly("com.h2database:h2")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

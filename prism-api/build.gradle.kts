import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.springframework.boot")
}

tasks.getByName<BootJar>("bootJar") {
    enabled = true
    archiveFileName.set("prism-api.jar")
}

tasks.test {
    useJUnitPlatform { excludeTags("mysql", "kafka") }
}

tasks.register<Test>("kafkaTest") {
    description = "Verifies asynchronous ingestion and warehouse output against a real Kafka broker"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("kafka") }
    outputs.upToDateWhen { false }
    doFirst { require(!System.getenv("PRISM_TEST_KAFKA_BOOTSTRAP").isNullOrBlank()) { "Set PRISM_TEST_KAFKA_BOOTSTRAP to a test broker" } }
}

// Opt-in: uses disposable databases on the MySQL instance supplied by the caller.
tasks.register<Test>("mysqlTest") {
    description = "Validates fresh schema and migrations against a real MySQL 8.0.17+ server"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("mysql") }
    outputs.upToDateWhen { false }
    doFirst {
        require(!System.getenv("PRISM_TEST_MYSQL_URL").isNullOrBlank()) {
            "Set PRISM_TEST_MYSQL_URL to a test server URL, for example jdbc:mysql://127.0.0.1:3306/"
        }
    }
}

dependencies {
    implementation(project(":prism-core"))
    implementation(project(":prism-common"))
    implementation(project(":prism-infrastructure"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.apache.kafka:kafka-clients")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":prism-sdk"))
    testRuntimeOnly("com.h2database:h2")
}

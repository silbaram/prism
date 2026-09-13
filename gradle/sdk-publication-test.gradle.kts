import org.gradle.api.publish.PublishingExtension

// Test real publications in independent builds, without inheriting this build's BOM or project dependencies.
val verificationRepository = layout.buildDirectory.dir("publication-test/repository")
val cleanVerificationRepository = tasks.register<Delete>("cleanSdkVerificationRepository") {
    delete(verificationRepository)
}
val producers = listOf(project(":prism-common"), project(":prism-core"), project(":prism-sdk"))
val publishTaskName = "publishMavenPublicationToSdkVerificationRepository"
producers.forEach { producer ->
    producer.plugins.withId("maven-publish") {
        producer.extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "sdkVerification"
                url = verificationRepository.get().asFile.toURI()
            }
        }
        producer.tasks.matching { it.name == publishTaskName }.configureEach {
            dependsOn(cleanVerificationRepository)
        }
    }
}

val consumerChecks = listOf("Module", "Pom").map { format ->
    val consumerDirectory = layout.buildDirectory.dir("publication-test/consumer-${format.lowercase()}")
    val prepareConsumer = tasks.register<Sync>("prepareSdk${format}Consumer") {
        from("src/test/consumer")
        into(consumerDirectory)
    }
    tasks.register<GradleBuild>("verifySdk${format}Publication") {
        group = "verification"
        description = "Compiles and runs a standalone Java SDK consumer using $format metadata"
        dependsOn(prepareConsumer)
        dependsOn(producers.map { "${it.path}:$publishTaskName" })
        dir = consumerDirectory.get().asFile
        buildName = "sdk-${format.lowercase()}-consumer"
        tasks = listOf("run")
        startParameter.projectProperties = mapOf(
            "prismVersion" to project.version.toString(),
            "prismRepository" to verificationRepository.get().asFile.toURI().toString(),
            "metadataFormat" to format.lowercase()
        )
        startParameter.isOffline = gradle.startParameter.isOffline
        startParameter.maxWorkerCount = gradle.startParameter.maxWorkerCount
    }
}

val verifySdkPublication = tasks.register("verifySdkPublication") {
    group = "verification"
    description = "Verifies published SDK artifacts with Gradle module metadata and Maven POM consumers"
    dependsOn(consumerChecks)
}
tasks.named("check") { dependsOn(verifySdkPublication) }

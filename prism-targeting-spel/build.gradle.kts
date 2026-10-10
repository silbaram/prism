plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":prism-core"))
    implementation("org.springframework:spring-expression:7.0.1")
}

java { withSourcesJar() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}

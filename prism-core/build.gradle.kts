plugins {
    `java-library`
    `maven-publish` // Maven 저장소 배포 플러그인
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.1")
}

// 소스 JAR 포함
java {
    withSourcesJar()
}

// Maven 퍼블리싱 설정
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

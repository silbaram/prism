plugins {
    id("org.springframework.boot")
    `java-library`
    `maven-publish` // Maven 저장소 배포 플러그인
}

tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
}

dependencies {
    implementation("org.springframework:spring-expression")
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

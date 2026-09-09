plugins {
    id("org.springframework.boot")
    `java-library`
    `maven-publish` // Maven 저장소 배포 플러그인
}

dependencies {
    api(project(":prism-core"))

    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-autoconfigure")

    api("com.mysql:mysql-connector-j")
}

tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
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

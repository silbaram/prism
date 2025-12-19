// Gradle 설정 파일: prism-spring-boot-starter 모듈
// - 이 모듈은 prism-sdk를 Spring Boot 스타터로 노출하기 위한 auto-configuration 제공 모듈입니다.
// - 로컬/원격에 퍼블리시하여 다른 프로젝트에서 의존성으로 사용할 수 있도록 구성되어 있습니다.

import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.gradle.jvm.tasks.Jar

plugins {
    id("org.springframework.boot")
    kotlin("kapt")
    `java-library`
    `maven-publish`
}

// 라이브러리로 배포하기 위해 bootJar 비활성화, 일반 jar 활성화
tasks.getByName<BootJar>("bootJar") {
    enabled = false
}

tasks.getByName<Jar>("jar") {
    enabled = true
}

dependencies {
    // Prism SDK를 api로 노출 (Starter 내부 래퍼가 사용)
    api(project(":prism-sdk"))

    // Spring AOP 지원 (@PrismExperiment 어노테이션 처리)
    // 주의: 4.0.0이 아직 Maven Central에 없어서 3.5.8 사용
    // api로 선언하여 AspectJ 의존성이 외부 프로젝트로 전이되도록 함
    api("org.springframework.boot:spring-boot-starter-aop:3.5.8")

    // Spring Boot AutoConfiguration
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.5.8")

    // Jackson Kotlin 모듈
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // Kotlin Logging (SLF4J Kotlin extension for lazy evaluation)
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")

    // Spring Boot Configuration Processor (IDE 자동완성 지원)
    kapt("org.springframework.boot:spring-boot-configuration-processor:3.5.8")

    // 테스트 의존성
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.8")
}

// 소스 JAR 포함
configure<JavaPluginExtension> {
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

// JUnit 플랫폼 사용
tasks.test {
    useJUnitPlatform()
}

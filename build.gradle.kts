import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.tasks.bundling.BootJar

val kotestVersion = "5.9.0"
val mockkVersion = "1.13.11"

plugins {
    id("org.springframework.boot") version "4.0.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.spring") version "2.2.0" apply false
    kotlin("plugin.jpa") version "2.2.0" apply false
}

allprojects {
    group = "io.github.silbaram.prism"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // 1. 모든 모듈 공통 플러그인
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring") // @Transactional 등 open 처리
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")    // Entity 기본생성자 처리
    apply(plugin = "io.spring.dependency-management")    // 버전 관리만 가져옴

    dependencies {
        "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("io.kotest:kotest-runner-junit5:$kotestVersion")
        "testImplementation"("io.kotest:kotest-assertions-core:$kotestVersion")
        "testImplementation"("io.mockk:mockk:$mockkVersion")
    }

    // 3. 자바 및 코틀린 컴파일 옵션
    val javaVersion = 21

    tasks.withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll(listOf("-Xjsr305=strict"))
            jvmTarget.set(JvmTarget.JVM_21)
            javaParameters.set(true)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
        // Toolchain을 쓰면 source/target compatibility는 보통 Toolchain을 따라가지만,
        // 명시적으로 적을 경우 버전을 맞추는 게 깔끔합니다.
        sourceCompatibility = JavaVersion.toVersion(javaVersion)
        targetCompatibility = JavaVersion.toVersion(javaVersion)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // 테스트 스캔 최적화는 좋은 설정입니다.
        jvmArgs("-Dkotest.framework.classpath.scanning.autoscan.disable=true")
    }
}

// PrismClient.trackConversion 직접 호출 금지 (PrismExperimentClient/trackConversionIfAssigned 사용)
val forbidDirectPrismClientTrackConversion by tasks.registering {
    group = "verification"
    description = "PrismClient.trackConversion 직접 호출을 금지합니다. 래퍼를 사용하세요."

    doLast {
        val root = rootDir
        val violations = mutableListOf<String>()

        fileTree(root) {
            include("**/*.kt")
            exclude("prism-sdk/**", "prism-spring-boot-starter/**", "**/build/**", "**/.gradle/**")
        }.forEach { file ->
            val content = file.readText()
            if (content.contains("import io.github.silbaram.prism.sdk.PrismClient")) {
                file.readLines().forEachIndexed { idx, line ->
                    if (line.contains("trackConversion(")) {
                        val relative = file.relativeTo(root).path
                        violations += "$relative:${idx + 1}"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            val message = buildString {
                appendLine("PrismClient.trackConversion 직접 호출 금지. PrismExperimentClient/trackConversionIfAssigned를 사용하세요.")
                violations.forEach { appendLine("- $it") }
            }
            throw GradleException(message)
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("forbidDirectPrismClientTrackConversion"))
    }
}

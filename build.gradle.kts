import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
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
    kotlin("kapt") version "2.2.0" apply false
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

    configure<DependencyManagementExtension> {
        imports { mavenBom(SpringBootPlugin.BOM_COORDINATES) }
    }

    // Dependency management populates the POM but does not export its BOM to Gradle module metadata.
    // Publish the versions used by this build for every library and both consumption variants.
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                versionMapping {
                    usage("java-api") { fromResolutionOf("runtimeClasspath") }
                    usage("java-runtime") { fromResolutionResult() }
                }
            }
        }
    }

    dependencies {
        "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("io.kotest:kotest-runner-junit5:$kotestVersion")
        "testImplementation"("io.kotest:kotest-assertions-core:$kotestVersion")
        "testImplementation"("io.mockk:mockk:$mockkVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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

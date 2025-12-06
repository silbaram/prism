import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("kapt")
    `java-library`  // 'api' 의존성 구성을 사용하기 위해 필요한 플러그인
    `maven-publish` // Maven 저장소 배포 플러그인
}

group = "io.github.silbaram.prism"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // [중요] api 사용: 이 스타터를 쓰는 프로젝트에서도 prism-sdk의 클래스(PrismClient 등)를 직접 사용할 수 있게 함
    api(project(":prism-sdk"))
    
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.0.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.0.0")
    kapt("org.springframework.boot:spring-boot-configuration-processor:4.0.0")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.0")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict"))
        jvmTarget.set(JvmTarget.JVM_24)
        javaParameters.set(true)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
    
    // IDE에서 소스 코드를 확인할 수 있도록 Source Jar 함께 배포
    withSourcesJar()
}

publishing {
    publications {
        // Maven 배포 설정
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

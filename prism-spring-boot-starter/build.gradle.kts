// Gradle 설정 파일: prism-spring-boot-starter 모듈
// - 이 모듈은 prism-sdk를 Spring Boot 스타터로 노출하기 위한 auto-configuration 제공 모듈입니다.
// - 로컬/원격에 퍼블리시하여 다른 프로젝트에서 의존성으로 사용할 수 있도록 구성되어 있습니다.
plugins {
    // KAPT: annotation processor 사용을 위해 필요 (spring-boot-configuration-processor 등)
    kotlin("kapt")

    // 라이브러리를 배포하기 위해 Java 컴포넌트(api/implementation 구분)를 제공
    `java-library`  // 'api' 의존성 구성을 사용하기 위해 필요한 플러그인

    // maven-publish: publishToMavenLocal / publish to remote repo 설정에 사용
    `maven-publish` // Maven 저장소 배포 플러그인
}

dependencies {
    // 스타터를 사용하는 애플리케이션에서도 Prism SDK 타입을 직접 사용하도록 노출
    // (PrismAutoConfiguration 등이 PrismClient 타입을 반환/주입할 수 있으므로 api로 노출)
    api(project(":prism-sdk"))

    // Kotlin용 Jackson 모듈: DTO 직렬화/역직렬화에 사용
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // Spring Boot 자동설정 의존성 (auto-configuration 구현에 필요)
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.0.0")

    // Spring Boot 설정 메타데이터 생성기: application.properties/yml에 대한 IDE 자동완성 지원
    // kapt 및 annotationProcessor를 통해 빌드 시 메타데이터를 생성
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.0.0")
    kapt("org.springframework.boot:spring-boot-configuration-processor:4.0.0")

    // 테스트용 경량 Kotlin 테스트 라이브러리 및 Spring Boot 테스트 스타터
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.0")
}

configure<JavaPluginExtension> {
    // 소스 JAR 포함: IDE에서 소스 확인 혹은 배포된 artifact에 소스가 필요할 때 사용
    withSourcesJar()
}

publishing {
    publications {
        // publications에 components["java"]를 지정하면 기본 jar, sourcesJar, pom 등이 퍼블리시 됩니다.
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// 테스트 런너 설정
tasks.test {
    // 루트 설정과 중복될 수 있으나 명시적으로 유지해도 무방
    useJUnitPlatform()
}

// prism-sdk 모듈 Gradle 설정
// - 이 모듈은 다른 프로젝트에서 라이브러리(artifact)로 사용되므로
//   `java-library` + `maven-publish`로 배포할 수 있도록 구성합니다.
// - `io.spring.dependency-management`는 의존성 버전 관리를 Root 또는 BOM 사용 시 보조 역할을 합니다.
plugins {
    // 로컬/원격에 퍼블리시할 때 Java 컴포넌트를 생성하기 위함
    `maven-publish`

    // 라이브러리로서 API/implementation 구분을 제공
    `java-library`
}

// 모듈 의존성
dependencies {
    // SDK를 사용하는 쪽에서도 이 클래스를 알 수 있도록 API 의존성으로 노출해야 합니다.
    api(project(":prism-common"))

    // JSON 직렬화 등 런타임 의존성
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // 테스트 의존성
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.11.0")
}

// 소스 JAR 포함: IDE나 외부 소비자가 소스 보기 위해 필요
java {
    withSourcesJar()
}

// Maven 퍼블리싱 설정 (publishToMavenLocal 등에 사용)
publishing {
    publications {
        // `components["java"]`로부터 생성된 Jar(및 sourcesJar)를 퍼블리시
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

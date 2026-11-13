plugins {
    `java-library`  // jar 컴포넌트 등록
    `maven-publish` // Maven 저장소 배포 플러그인
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations")

    // 테스트 전용: DTO JSON 계약 검증 (버전은 저장소 캐시 기준; 공통 모듈에는 Boot BOM 미적용이라 명시)
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
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

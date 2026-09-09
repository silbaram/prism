plugins {
    `java-library`  // jar 컴포넌트 등록
    `maven-publish` // Maven 저장소 배포 플러그인
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations")
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

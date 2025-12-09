plugins {
    id("org.springframework.boot")
    `java-library`
}

// prism-infrastructure는 이제 상위 모듈로서 서브모듈들을 관리합니다.
// 실제 구현은 각 서브모듈(persistence-jpa, cache, redis 등)에 있습니다.
dependencies {
    // 하위 서브모듈들에 대한 의존성
    // 예: api(project(":prism-infrastructure:persistence-jpa"))
}

tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
}

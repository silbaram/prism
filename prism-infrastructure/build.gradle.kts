plugins {
    id("org.springframework.boot")
    `java-library`
}

dependencies {
    api(project(":prism-core"))

    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-autoconfigure")

    // H2는 runtime으로 각 애플리케이션 모듈에서 추가
    compileOnly("com.h2database:h2")
    
    api("com.mysql:mysql-connector-j")
}

tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
}

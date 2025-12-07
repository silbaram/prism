plugins {
    id("org.springframework.boot")
    `java-library`
}

dependencies {
    api(project(":prism-core"))

    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-autoconfigure")

    api("com.mysql:mysql-connector-j")
}

tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
}

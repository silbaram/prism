# Prism Spring Boot Starter

`prism-spring-boot-starter`는 Prism SDK를 Spring Boot 애플리케이션에서 손쉽게 사용할 수 있도록 자동 설정을 제공하는 스타터 모듈입니다.

## Installation

Gradle 프로젝트의 `build.gradle.kts`에 의존성을 추가합니다:

```kotlin
dependencies {
    implementation("io.github.silbaram.prism:prism-spring-boot-starter:0.0.1-SNAPSHOT")
}
```

## Configuration

`application.yml` 또는 `application.properties` 파일에 Prism API 서버의 URL을 설정해야 합니다.

### application.yml

```yaml
prism:
  client:
    url: "http://localhost:8080"  # [필수] Prism API 서버 주소
    timeout: 5s                   # [선택] 연결 및 읽기 타임아웃 (기본값: 5초)
```

### Properties

| Property | Required | Default | Description |
|---|---|---|---|
| `prism.client.url` | **Yes** | - | Prism API 서버의 Base URL입니다. |
| `prism.client.timeout` | No | `5s` | API 요청 타임아웃 시간입니다. |

## Usage

설정을 마치면 `PrismClient` 빈(Bean)이 자동으로 등록됩니다. 생성성 주입 등을 통해 바로 사용할 수 있습니다.

```kotlin
import io.github.silbaram.prism.sdk.PrismClient
import org.springframework.stereotype.Service

@Service
class MyService(
    private val prismClient: PrismClient
) {

    fun checkUserVariant(userId: String) {
        val response = prismClient.assign(userId, "my-experiment-key")
        
        if (response.resultCode == "0000") {
            println("User variant: ${response.variant}")
        } else {
            println("Error: ${response.resultMessage}")
        }
    }
}
```

# Prism SDK (Java/Kotlin 클라이언트)

Prism SDK는 Prism A/B 테스트 플랫폼을 Java/Kotlin 애플리케이션에서 사용할 수 있게 해주는 경량 클라이언트입니다. 모든 호출이 **Fail-safe**로 동작하도록 설계되어, 네트워크 오류나 서버 장애 시에도 비즈니스 로직이 중단되지 않습니다.

## 설치
Gradle 프로젝트에 의존성을 추가합니다.
```kotlin
dependencies {
    implementation("io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT")
}
```

## 클라이언트 초기화
```kotlin
import io.github.silbaram.prism.sdk.PrismClient
import java.time.Duration

val prismClient = PrismClient(
    baseUrl = "http://localhost:8081", // Prism API 서버 주소
    timeout = Duration.ofSeconds(5)    // 선택: 기본 5초
)
```

## Variant 할당 (`assign`)
- 성공 시 `AssignmentResponse.variant`와 `resultCode="0000"`을 반환합니다.
- API/네트워크 오류 시 예외 대신 `variant=null`, `resultCode="9999"` 응답을 반환하며 로그에만 기록합니다.
```kotlin
val response = prismClient.assign("user-123", "exp-1")
val variant = response.variant ?: "control" // null이면 기본값 사용
```
```java
AssignmentResponse response = prismClient.assign("user-123", "exp-1");
String variant = response.getVariant() != null ? response.getVariant() : "control";
```

## 전환 추적
Prism API의 `/v1/conversions`에 전환 이벤트를 비동기적으로 기록합니다. 실패해도 예외를 던지지 않습니다.

**주의:** 통계 오염 방지를 위해 `PrismClient.trackConversion`을 직접 호출하지 마세요. 대신 `PrismExperimentClient`의 안전한 래퍼를 사용하세요.

## 오류 처리 특징
- HTTP 4xx/5xx, 타임아웃, 기타 예외 상황에서도 응답 객체를 반환하여 호출 측 로직을 깨지 않습니다.
- 로그에는 마스킹된 userId만 기록합니다(길이 > 4인 경우 앞 2자리/뒤 2자리만 노출).
- `timeout` 값은 PrismClient 생성 시 Duration으로 조정할 수 있습니다.

## 테스트
SDK 단위 테스트 실행:
```bash
./gradlew :prism-sdk:test
```

## 안전 래퍼 사용하기 (전환 오염 방지)
`PrismExperimentClient`는 `PrismClient`를 감싸서 할당 성공 여부(`assigned`)를 함께 제공하고, 할당된 경우에만 전환을 기록합니다.

### 기본 사용법
```kotlin
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient

val prismClient = PrismClient("http://localhost:8081")
val experimentClient = PrismExperimentClient(prismClient)

// 1. 사용자에게 variant 할당
val outcome = experimentClient.assign("user-123", "exp-1")
val variant = outcome.variant ?: "control"

// 2. variant에 따라 비즈니스 로직 실행
when (variant) {
    "A" -> showFeatureA()
    "B" -> showFeatureB()
    else -> showControl()
}

// 3. 전환 추적 (할당된 경우에만 자동으로 기록됨)
experimentClient.track(outcome, "purchase")  // 짧은 별칭
```

### 다양한 전환 추적 방법
```kotlin
// 방법 1: 짧은 별칭 (권장)
experimentClient.track(outcome, "purchase")

// 방법 2: Extension function (더 간결)
outcome.track(experimentClient, "purchase")

// 방법 3: 전체 이름 (명시적)
experimentClient.trackConversionIfAssigned(outcome, "purchase")
```

세 가지 방법 모두 동일하게 동작하며, **할당이 성공한 경우에만** 전환을 기록하여 통계 오염을 방지합니다.

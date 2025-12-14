# Prism Spring Boot Starter

`prism-spring-boot-starter`는 Prism SDK를 Spring Boot 애플리케이션에 자동 구성하고, 어노테이션 기반 실험 적용과 안전한 전환 추적을 지원합니다.

## 설치
Gradle에 의존성을 추가합니다.
```kotlin
dependencies {
    implementation("io.github.silbaram.prism:prism-spring-boot-starter:0.0.1-SNAPSHOT")
}
```

## 설정
`prism.client.url`이 설정되어야 `PrismClient` 빈이 등록됩니다. `timeout`은 선택 항목입니다.
```yaml
prism:
  client:
    url: http://localhost:8081   # 필수: Prism API 서버 주소
    timeout: 5s                 # 선택: 기본 5초
```

## 자동 구성되는 빈
- `PrismClient`: `prism.client.url`이 있을 때 생성되며 SDK의 fail-safe 동작을 그대로 제공합니다.
- `PrismExperimentAspect`: `@PrismExperiment` 메소드를 가로채 variant를 조회하고 `PrismContext`에 저장합니다.
- `PrismConversionTracker`: 실제 할당된 경우에만 전환을 기록하는 안전한 래퍼(컴포넌트 등록).

## 어노테이션 기반 사용법
1) 서비스/도메인 로직에 `@PrismExperiment`를 붙입니다.  
2) `@PrismUserId` 또는 `userIdParam`으로 사용자 식별자를 지정합니다.  
3) 메소드 내부에서 `PrismContext.getCurrentVariant()`로 variant를 꺼내 로직을 분기합니다.
```kotlin
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.github.silbaram.prism.starter.aop.PrismContext
import org.springframework.stereotype.Service

@Service
class DiscountService {
    @PrismExperiment(experimentKey = "discount_ab")
    fun calculate(@PrismUserId userId: String, amount: Int): Int {
        val variant = PrismContext.getCurrentVariant() ?: "control" // 할당 실패 시 직접 기본값 결정
        return when (variant) {
            "A" -> (amount * 0.9).toInt()
            "B" -> (amount * 0.8).toInt()
            else -> amount
        }
    }
}
```
- `userId` 파라미터를 직접 지정하려면 `@PrismExperiment(..., userIdParam = "customId")` 형태로 설정합니다.
- `userId`가 없거나 null이면 실험 할당을 스킵하며 예외를 던지지 않습니다. 이 경우 `PrismContext.getCurrentVariant()`는 `null`이므로 비즈니스 로직에서 기본값을 지정하세요(`?: "control"` 등).
- Prism API 실패 시 `getCurrentVariant()`는 `null`이 될 수 있으니 비즈니스 로직에서 직접 기본값을 정하세요(`?: "control"` 등).
- ID 추출 규칙:
  - `@PrismUserId`가 붙은 파라미터가 우선입니다.
  - 없으면 `userIdParam` 이름과 일치하는 파라미터를 사용합니다.
  - 값이 `null`/빈 문자열이면 할당을 건너뛰고 경고 로그만 남깁니다.
  - 쿠키 기반이라면 `@CookieValue(value = "uid", defaultValue = "") @PrismUserId uid: String`처럼 기본값을 두거나 `required = true`로 설정해 누락을 방지하세요.

## 안전한 전환 추적
`PrismConversionTracker.trackConversionSafe`는 `PrismContext.wasActuallyAssigned()`가 `true`인 경우에만 전환을 기록해 통계 오염을 방지합니다.
```kotlin
import io.github.silbaram.prism.starter.service.PrismConversionTracker

@Service
class CheckoutService(
    private val conversionTracker: PrismConversionTracker
) {
    @PrismExperiment(experimentKey = "checkout-flow")
    fun complete(@PrismUserId userId: String) {
        // ... 구매 처리 ...
        conversionTracker.trackConversionSafe(userId, "checkout-flow", "purchase")
    }
}
```

## 테스트
스타터 단위 테스트 실행:
```bash
./gradlew :prism-spring-boot-starter:test
```

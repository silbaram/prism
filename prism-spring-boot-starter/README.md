# Prism Spring Boot Starter

Spring Boot 4 / JDK 21 기반의 할당, 전략 선택, 전환 추적 통합입니다.

## 설정

```kotlin
implementation("io.github.silbaram.prism:prism-spring-boot-starter:0.0.1-SNAPSHOT")
```

```yaml
prism:
  client:
    url: http://localhost:8080
    timeout: 2s
    assignment-cache-ttl: 30s
    assignment-cache-maximum-size: 10000
```

URL이 설정되면 `PrismClient`, `PrismExperimentClient`, `PrismExperimentAspect`, `PrismTrackConversionAspect`, `PrismConversionTracker`, `PrismStrategyResolver`가 생성됩니다. 사용자 정의 빈은 자동 구성보다 우선합니다.

캐시는 전환 추적의 **기존 노출 조회**에 사용합니다. 실패는 캐시하지 않고, 키는 사용자·실험 조합입니다. TTL `0s` 또는 최대 크기 `0`으로 끌 수 있습니다. 명시적인 할당은 매번 서버에서 확인합니다.

## 1. 명시적으로 변형 선택

```kotlin
@Service
class CheckoutService(private val experiments: PrismExperimentClient) {
    fun price(userId: String, amount: Int): Int {
        val outcome = experiments.assign(userId, "checkout")
        return when (if (outcome.assigned) outcome.variant else null) {
            "B" -> amount * 90 / 100
            else -> amount
        }
    }
}
```

`assign`은 실험 기능을 실제 노출하는 시점에 호출하세요. 할당 실패 시 업무에 맞는 기본 동작을 선택합니다. 현재 변형을 읽기 위한 ThreadLocal 컨텍스트는 없습니다.

## 2. 어노테이션으로 노출과 이벤트 기록

```kotlin
@Service
class CheckoutEvents {
    @PrismExperiment(experimentKey = "checkout")
    fun showPage(@PrismUserId userId: String) {
        // 공통 페이지 노출. 변형별 동작은 명시적 클라이언트/전략을 사용합니다.
    }

    @PrismTrackConversion(
        experimentKey = "checkout", eventName = "purchase",
        trackWhen = TrackCondition.RETURN_TRUE
    )
    fun purchase(@PrismUserId userId: String, successful: Boolean): Boolean = successful
}
```

Admin에서 `checkout`의 목표 이벤트를 `purchase`로 설정하세요. `purchase`는 `showPage`와 다른 요청·스레드에서 실행돼도 기존 노출을 조회해 기록합니다. 노출이 없으면 건너뛰며 새 할당을 만들지 않습니다. 두 어노테이션을 같은 메서드에 붙여도 Aspect 순서 설정이 필요하지 않습니다.

각 어노테이션 메서드에는 정확히 하나의 `@PrismUserId` 파라미터가 필요합니다. JDK/CGLIB 프록시 모두 구현 메서드·인터페이스·상위 클래스의 대응 파라미터에서 찾으며, 제네릭 브리지도 처리합니다. 같은 위치의 반복 선언은 허용하고 누락·서로 다른 위치의 중복·null·공백이면 업무 메서드 실행 전에 `IllegalArgumentException`을 던집니다. 파라미터 이름 추론은 사용하지 않습니다. `@PrismExperiment`와 `@PrismTrackConversion`은 구현 메서드에 선언하세요.

Spring AOP는 Spring 빈의 프록시를 거친 외부 호출에 적용됩니다. 같은 객체 내부의 직접 호출에는 적용되지 않습니다. `suspend` 함수나 비동기 결과의 완료 감지는 제공하지 않으므로, 완료 시점의 코드에서 명시적인 추적 메서드를 호출하세요.

### 보조 이벤트와 조건

```kotlin
@PrismTrackConversion("checkout", "purchase", trackWhen = TrackCondition.RETURN_TRUE)
@PrismTrackConversion("checkout", "payment_failed", trackWhen = TrackCondition.RETURN_FALSE)
fun pay(@PrismUserId userId: String, successful: Boolean): Boolean = successful
```

`purchase`만 목표 이벤트로 집계되고 `payment_failed`는 별도 이벤트 화면에 표시됩니다. 이벤트 이름은 대소문자를 구분합니다. 사용자별 반복 이벤트는 CVR에 한 번만 반영됩니다.

| 조건 | 정상 반환 시 기록 조건 |
|---|---|
| `ALWAYS` | 항상 |
| `RETURN_TRUE` | Boolean `true` |
| `RETURN_FALSE` | Boolean `false` |
| `NOT_NULL` | null이 아닌 값 |
| `IS_NULL` | null |

예외 발생 시 기본적으로 기록하지 않습니다. `trackOnException=true`와 `ALWAYS`를 함께 지정하면 예외에도 기록합니다. 예외에는 반환값이 없으므로 다른 반환값 조건은 평가하지 않습니다. 실패 추적에는 성공 목표와 별개의 이벤트 이름을 사용하세요. 추적의 통신 오류는 원래 결과나 업무 예외를 바꾸지 않습니다.

## 3. 전략 클래스 선택

```kotlin
import io.github.silbaram.prism.starter.strategy.resolve

interface PricingStrategy { fun price(amount: Int): Int }

@PrismStrategy(variant = "control", experimentKey = "checkout")
class ControlPricing : PricingStrategy {
    override fun price(amount: Int) = amount
}

@PrismStrategy(variant = "B", experimentKey = "checkout")
class DiscountPricing : PricingStrategy {
    override fun price(amount: Int) = amount * 90 / 100
}

@Service
class PricingService(private val resolver: PrismStrategyResolver) {
    fun price(userId: String, amount: Int): Int =
        resolver.resolve<PricingStrategy>(userId, "checkout").price(amount)
}
```

Resolver는 Spring 빈의 프록시 자체를 반환하므로 트랜잭션·캐시 등의 advice가 유지됩니다. 할당 실패 또는 대응 전략이 없으면 `control`로 폴백하며, control도 없으면 예외입니다. 실험에 설정한 모든 변형의 전략을 제공해야 실제 실행과 노출의 귀속이 일치합니다.

## 명시적인 전환 추적

```kotlin
val accepted = conversionTracker.trackConversionSafe("user-123", "checkout", "purchase")
// 또는 experiments.trackIfAssigned(...), experiments.track(outcome, "purchase")
```

어노테이션 내부에서만 호출할 필요가 없습니다. 서버에 선행 노출이 있어야 하고, 반환값은 서버의 수락 여부입니다. HTTP 200 본문에서 전환이 거부되면 `false`입니다.

## 이전 버전에서 이동

- `PrismContext.getCurrentVariant()` → 실제 노출 시 `PrismExperimentClient.assign()`의 반환값을 명시적으로 전달하거나 Strategy 사용.
- `PrismContext.wasActuallyAssigned()` → `AssignmentOutcome.assigned` 또는 `trackIfAssigned` 사용.
- `@PrismVariantMethod` / `PrismVariantMethodRouter.route(...)` → 공통 인터페이스와 `@PrismStrategy` 구현 클래스로 이동.
- `userIdParam` 속성 → 해당 파라미터에 `@PrismUserId` 추가.
- `PrismConversionTracker` / 전환 Aspect 수동 생성 → `PrismClient` 대신 `PrismExperimentClient` 전달.

공개 API가 변경됐으므로 소비자를 다시 컴파일해야 합니다. [스키마와 API 업그레이드 순서](../docs/issue-27.md)를 먼저 확인하세요.

```bash
./gradlew :prism-spring-boot-starter:test
```

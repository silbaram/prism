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
    evaluation-mode: LOCAL
    config-sync-interval: 60s
    initialization-timeout: 5s
    event-flush-interval: 5s
    event-batch-size: 100
    event-queue-capacity: 10000
    exposure-cache-maximum-size: 10000
    shutdown-timeout: 5s
    assignment-cache-ttl: 30s
    assignment-cache-maximum-size: 10000
```

URL이 설정되면 `PrismClient`, `PrismExperimentClient`, `PrismExperimentAspect`, `PrismTrackConversionAspect`, `PrismConversionTracker`, `PrismStrategyResolver`가 생성됩니다. 사용자 정의 빈은 자동 구성보다 우선합니다.

캐시는 전환 추적의 **기존 노출 조회**에 사용합니다. 실패는 캐시하지 않고, 키는 사용자·실험 조합입니다. TTL `0s` 또는 최대 크기 `0`으로 끌 수 있습니다. 기본 LOCAL 모드의 명시적 할당은 동기화된 설정으로 로컬 평가하고 노출을 큐에 등록합니다. Spring 컨텍스트 종료 시 client를 닫아 제한 시간 안에 이벤트 전송을 시도합니다. 초기화 대기 없이 즉시 실패하려면 `initialization-timeout: 0s`를 사용하세요.

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

Admin에서 `checkout`의 목표 이벤트를 `purchase`로 설정하세요. `purchase`는 같은 client를 공유하는 다른 요청·스레드에서 실행돼도 기존 노출을 조회해 기록합니다. LOCAL 모드도 참조가 없으면 서버에 이미 저장된 배치 노출을 읽기 전용으로 조회합니다. 노출이 없으면 건너뛰며 새 할당을 만들지 않습니다. 두 어노테이션을 같은 메서드에 붙여도 Aspect 순서 설정이 필요하지 않습니다.

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

어노테이션 내부에서만 호출할 필요가 없습니다. LOCAL 모드에는 로컬 기록 또는 서버 조회로 확인한 선행 노출 참조가 필요하고, 반환값은 로컬 큐 등록 여부입니다. 실제 전송 결과는 `PrismClient.flush()`와 거부 로그로 확인합니다. 이벤트 ID가 없는 구버전 노출을 조회하는 기존 방식은 `evaluation-mode: REMOTE`를 사용하며 이때 반환값은 서버 수락 여부입니다.

`assignment-cache-ttl`은 LOCAL 모드의 내부 노출 참조에도 적용됩니다. 만료 후에는 발생 시각이 가장 최근인 노출을 다시 조회하고, 같은 시각이면 이벤트 ID로 선택합니다. 아직 전송 중인 로컬 노출은 ACK까지 보존합니다. 서버 조회 중에도 같은 사용자의 로컬 할당은 진행할 수 있습니다.

`experiments.track(outcome, eventName)`은 해당 할당 결과의 노출 ID에 고정하여 추적합니다. 이후 재할당되거나 결과를 다른 인스턴스에 전달해도 원래 노출에 연결됩니다. `trackIfAssigned`와 어노테이션은 최근 노출 조회를 사용하는 경로입니다.

## 이전 버전에서 이동

- `PrismContext.getCurrentVariant()` → 실제 노출 시 `PrismExperimentClient.assign()`의 반환값을 명시적으로 전달하거나 Strategy 사용.
- `PrismContext.wasActuallyAssigned()` → `AssignmentOutcome.assigned` 또는 `trackIfAssigned` 사용.
- `@PrismVariantMethod` / `PrismVariantMethodRouter.route(...)` → 공통 인터페이스와 `@PrismStrategy` 구현 클래스로 이동.
- `userIdParam` 속성 → 해당 파라미터에 `@PrismUserId` 추가.
- `PrismConversionTracker` / 전환 Aspect 수동 생성 → `PrismClient` 대신 `PrismExperimentClient` 전달.

기본 평가 모드는 LOCAL입니다. 속성 기반 타기팅에는 명시적 `experiments.assign(userId, key, attributes)`를 사용하세요. 현재 어노테이션은 사용자 ID만 추출합니다. 공개 API가 변경됐으므로 소비자를 다시 컴파일해야 합니다. [스키마와 API 업그레이드 순서](../docs/issue-28-phase-1.md)를 먼저 확인하세요.

```bash
./gradlew :prism-spring-boot-starter:test
```

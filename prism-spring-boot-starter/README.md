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
    api-key: ${PRISM_CLIENT_API_KEY}
    timeout: 2s
    evaluation-mode: LOCAL
    config-sync-interval: 60s
    initialization-timeout: 5s
    event-flush-interval: 5s
    event-batch-size: 100
    event-queue-capacity: 10000
    exposure-cache-maximum-size: 10000
    exposure-dedup-capacity: 100000
    flush-timeout: 5s      # 평상시 flush의 전체 시간 예산
    shutdown-timeout: 5s   # 종료 시 마지막 flush의 별도 예산
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

정상 반환 조건을 만족한 전환은 Spring의 동기 트랜잭션이 있으면 **커밋 성공 후** 추적합니다. `rollback-only`, 외부 메서드의 예외, DB 커밋 실패에는 성공 전환을 보내지 않습니다. `REQUIRES_NEW`는 자체 커밋을 따르며, `NESTED`나 Spring 세이브포인트로 취소된 작업은 바깥 트랜잭션이 커밋되어도 제외합니다. 추적 advice는 Spring 트랜잭션 advice 안에서 실행되도록 가장 낮은 우선순위를 사용합니다. 트랜잭션이 없으면 정상 반환 직후 추적하며, 활성 트랜잭션의 완료 알림을 등록할 수 없으면 경고 후 건너뜁니다. 명시적인 `PrismConversionTracker` / SDK 호출은 자동으로 지연되지 않으므로 커밋 후 호출하세요.

자동 구성은 Spring 빈으로 관리되는 동기 `ConfigurableTransactionManager`의 완료 상태도 확인합니다. 이미 커밋된 `afterCommit` 콜백이나 `@TransactionalEventListener(AFTER_COMMIT)` 안에서 `@PrismTrackConversion`을 실행하면 즉시 추적하며, 새 `REQUIRES_NEW` 트랜잭션을 열면 그 트랜잭션의 커밋을 기다립니다. Spring이 관리하지 않는 트랜잭션 관리자 또는 직접 구성한 Aspect에서는 완료 콜백 안의 추적에 명시적인 SDK/Tracker를 사용하세요.

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

Resolver는 Spring 빈의 프록시 자체를 반환하므로 트랜잭션·캐시 등의 advice가 유지됩니다. **양수 비중의 모든 변형**에 전략이 있어야 노출을 등록합니다. 하나라도 빠지면 전체 실험의 배정을 거부하고 노출 없이 `control`로 폴백합니다. 기존 노출이 있어도 같은 client의 해당 실험 전환은 거부합니다. 0% 변형은 전략을 생략할 수 있지만, sticky 저장소에서 해당 변형이 반환되면 배정을 거부합니다. control도 없으면 예외이며, 중복 전략은 노출 전에 설정 오류로 처리합니다.

전략 목록은 client 수명 동안 고정입니다. 같은 실험을 여러 인터페이스에서 사용하면 모두 지원하는 변형만 허용하므로 각 인터페이스에 전체 전략을 제공하세요. 목록을 바꾸려면 애플리케이션/client를 재시작합니다. REMOTE 모드는 새 `/v1/assign/supported`, `/v1/conversions/supported`가 필요하므로 API를 먼저 업그레이드하세요. 구버전 API에서는 노출을 기록하지 않고 폴백하며, 기존 API로 자동 재시도하지 않습니다.

Resolver가 노출을 직접 등록하므로 같은 경험에 `@PrismExperiment`를 중복 적용하지 마세요. 전략 제약을 등록한 뒤에는 같은 client의 일반 SDK 배정·평가·노출 등록에도 그 제약이 적용됩니다. 잘못된 전략 이름이나 빈 목록은 해당 실험의 추적을 차단하므로 설정을 수정한 뒤 재시작해야 합니다.

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

기본 평가 모드는 LOCAL입니다. 속성 기반 타기팅에는 명시적 `experiments.assign(userId, key, attributes)`를 사용하세요. 현재 어노테이션은 사용자 ID만 추출합니다. 공개 API가 변경됐으므로 소비자를 다시 컴파일해야 합니다. [DB 업그레이드 순서](../README.md#db-업그레이드)를 먼저 확인하세요.

```bash
./gradlew :prism-spring-boot-starter:test
```

LOCAL 노출은 공유 client 수명 동안 사용자×실험별로 중복 제거합니다. `exposure-dedup-capacity` (기본 100,000) 도달 시 새 조합의 `assign()`은 `SdkResponseCode.EXPOSURE_DEDUP_CAPACITY_REACHED` (`9998`)를 반환하고 `AssignmentOutcome.assigned`는 `false`가 됩니다. 일반 client 오류 (`9999`)와 구분해 모니터링하세요. 이미 기록한 조합과 전환은 계속 처리하며 ACK·설정 갱신으로 항목을 지우거나 자동 퇴출하지 않습니다. 모집단 노출은 별도 목록에 같은 한도를 적용하며, 가득 차면 `recordPopulationExposure()`가 `false`를 반환합니다.

자동 구성된 `PrismClient` 빈을 주입해 `exposureDedupCount`, `populationExposureDedupCount`, `exposureDedupRejectedCount`, `populationExposureDedupRejectedCount`를 수집하세요. 앞의 두 값은 현재 항목 수이고 뒤의 두 값은 용량 때문에 거절된 누적 호출 수(재시도 포함)입니다. `pendingEventCount`는 전송 큐 크기이므로 한도 사용량을 나타내지 않습니다. 경고 로그는 목록별 최대 1분에 한 번이며 현재 사용량·한도·이전 경고 이후 거절 수·총 거절 수를 포함합니다. 다음 거절 호출이 없으면 추가 경고가 출력되지 않으므로 카운터 증가와 사용량 비율에도 경보를 설정하세요.

일일 사용자 수보다 **client 수명 동안 해당 인스턴스에 도달하는 누적 고유 사용자×실험 조합 수**와 여유분으로 한도를 정하세요. 동일 조합의 재방문은 추가되지 않지만 신규 사용자와 신규 실험은 누적됩니다. 모집단 목록은 누적 고유 사용자 수를 별도로 고려하세요. 한도를 높이기 전 분석 메타데이터를 포함한 실제 힙 사용량을 측정하세요. [SDK 사용량·사이징 설명](../prism-sdk/README.md)을 참고하세요.

어노테이션과 Strategy는 실제 경험을 제공하는 시점에 사용합니다. 화면 준비와 노출이 다르면 SDK의 `evaluate()` / `recordExposure()`로 분리하세요. [SDK 운영 계약](../README.md#sdk-운영-계약)을 참고하세요.

Phase 3 API는 설정·이벤트·기존 원격 API 모두 키를 요구합니다. 키는 서버의 `PRISM_API_KEYS`와 일치해야 합니다. 참여 비율·기간을 쓰기 전에 모든 소비자를 새 SDK/스타터로 갱신하세요. [DB 업그레이드](../README.md#db-업그레이드)와 [인증 안내](../README.md#인증)를 참고하세요.

## Phase 4 선택 설정

```yaml
prism:
  client:
    config-streaming: true
    sticky-assignments-directory: /var/lib/my-app/prism-prod
```

SSE는 정기 폴링을 유지하면서 변경 전파를 빠르게 합니다. 저장소 디렉터리를 생략하면 최초 배정은 메모리에만 유지합니다. 디렉터리는 환경별로 분리하고 영속 볼륨을 사용하세요. 여러 호스트 간 공유는 사용자 정의 `StickyAssignmentStore`를 사용하는 `PrismClient` 빈으로 구성합니다.

레이어와 영구 홀드아웃은 Admin에서 설정합니다. 누적 효과 계측은 `PrismExperimentClient.recordPopulationExposure`/`trackPopulationConversion`으로 명시적으로 실행합니다. [레이어와 홀드아웃 안내](../README.md#레이어와-홀드아웃)를 참고하세요.

## Phase 5 고급 분석

주입된 `PrismExperimentClient`의 `assign(userId, experimentKey, attributes, analysis)`에 `ExposureAnalysisContext`를 전달해 사전 세그먼트와 CUPED 지표를 수집합니다. 어노테이션은 이 정보를 자동 추출하지 않습니다. 실험 시작 전에 Admin에서 분석 계획을 고정해야 하며, [DB 업그레이드](../README.md#db-업그레이드)와 전체 API/Admin 배포를 먼저 완료하세요. [고급 분석과 계측 가이드](../README.md#고급-분석)를 참고하세요.

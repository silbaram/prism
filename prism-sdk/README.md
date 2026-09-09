# Prism SDK

JDK 21 이상에서 사용하는 동기식 Java/Kotlin 클라이언트입니다. HTTP 오류·타임아웃·잘못된 응답은 실패 결과로 반환하며, 전환 실패가 업무 로직에 예외를 전파하지 않습니다.

Spring Boot 없이도 사용할 수 있습니다. Gradle 모듈 메타데이터와 Maven POM에 빌드에서 해석된 의존성 버전을 기록하므로, SDK 사용을 위해 별도 Spring Boot BOM을 추가할 필요가 없습니다.

## 시작하기

```kotlin
implementation("io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT")
```

```kotlin
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import java.time.Duration

val transport = PrismClient("http://localhost:8080", Duration.ofSeconds(2))
val experiments = PrismExperimentClient(
    transport,
    assignmentCacheTtl = Duration.ofSeconds(30),
    assignmentCacheMaximumSize = 10_000
)

// 실험 기능을 실제로 노출하는 시점에 할당합니다.
val outcome = experiments.assign("user-123", "checkout")
val variant = if (outcome.assigned) outcome.variant else "control"
// variant에 맞는 화면/로직 실행

// 구매 성공 시 호출. Admin에서 checkout의 목표 이벤트를 purchase로 설정합니다.
val accepted = experiments.track(outcome, "purchase")
```

클라이언트는 애플리케이션에서 재사용하세요. 실험 정의·가중치가 같을 때 동일 사용자는 같은 변형에 할당됩니다. 정의가 바뀌면 할당도 바뀔 수 있습니다.

## 별도 요청에서 전환 추적

```kotlin
val accepted = experiments.trackIfAssigned("user-123", "checkout", "purchase")
```

`trackIfAssigned`는 사용자와 실험의 기존 노출을 확인합니다. 캐시에 없으면 `GET /v1/assignments`로 조회하며, 새 할당이나 노출을 만들지 않습니다. 따라서 구매 시점에 처음 유입된 사용자를 실험에 소급 편입하지 않습니다. 다른 요청·스레드·프로세스에서도 서버에 노출이 있으면 추적할 수 있습니다.

캐시는 성공한 할당/조회만 최대 10,000개, 기록 후 30초 동안 유지합니다. `Duration.ZERO` 또는 최대 크기 `0`은 캐시를 끕니다. 실패한 조회는 보관하지 않고, 전환 거부/오류 시 해당 항목을 무효화합니다. 같은 키의 동시 조회는 Caffeine의 원자적 로딩으로 합쳐집니다.

명시적인 `assign()`은 매번 서버를 호출하고 노출을 기록합니다. 캐시로 실험 중지나 정의 변경 확인을 건너뛰지 않습니다. 캐시된 노출은 전환 시도의 근거일 뿐이며, 서버가 최신 노출을 조회해 최종 귀속과 수락 여부를 결정합니다.

## API와 결과

| 메서드 | 동작 | 실패 결과 |
|---|---|---|
| `PrismClient.assign(userId, experimentKey)` | 할당 및 노출 저장 | 실패 `AssignmentResponse` |
| `PrismClient.getAssignment(userId, experimentKey)` | 기존 노출만 조회 | 노출 없음 또는 통신 실패 코드 |
| `PrismClient.trackConversion(userId, experimentKey, eventName)` | 서버 검증 후 이벤트 저장 | `false` |
| `PrismExperimentClient.assign(...)` | `AssignmentOutcome` 반환, 성공 결과 캐시 | `assigned=false` |
| `PrismExperimentClient.track(outcome, eventName)` | 성공한 할당 결과로 전환 요청 | `false` |
| `PrismExperimentClient.trackIfAssigned(...)` | 기존 노출 확인 후 전환 요청 | `false` |

전환 메서드의 `true`는 서버가 기록을 수락했다는 뜻입니다. HTTP 200이어도 본문의 `resultCode`가 실패이면 `false`입니다. 전환을 자동 재시도하지 않습니다. 네트워크 응답 유실 시 서버에 이미 저장됐을 수 있으며, 반복 요청은 이벤트 횟수를 늘릴 수 있지만 사용자 단위 CVR에는 중복 반영되지 않습니다.

| 결과 코드 | 위치 | 의미 |
|---|---|---|
| `0000` | `ResponseCode.SUCCESS` | 성공 |
| `9000` | `ResponseCode.EXPERIMENT_NOT_FOUND` | 실험 없음/비활성 |
| `9100` | `ResponseCode.IMPRESSION_NOT_FOUND` | 사용자·실험의 선행 노출 없음 |
| `9999` | `SdkResponseCode.CLIENT_ERROR` | SDK 내부 통신/응답 처리 실패 |

서버는 노출 없는 전환을 저장하지 않습니다. 저수준 `trackConversion()` 직접 호출에도 동일한 규칙이 적용됩니다. 이전의 소스 문자열 검사 Gradle 태스크는 제거했습니다.

## 지표 해석

Admin의 CVR은 지정한 **목표 이벤트 발생 고유 사용자 / 노출 고유 사용자 × 100**입니다. 각 변형에서 사용자당 한 번씩 집계합니다. 이벤트 이름은 대소문자를 구분하며, 선행 노출 없는 과거 이벤트는 제외합니다.

`page_viewed`, `payment_failed` 등의 보조 이벤트를 기록할 수 있지만 목표 이벤트와 다른 이름을 사용하세요. 보조 이벤트는 이벤트별 화면에만 표시됩니다. 동일 사용자의 이벤트를 합산해 CVR을 계산하지 않습니다. 목표 미설정 또는 노출 0명일 때 CVR은 미표시됩니다.

## 스레드와 비동기 사용

클라이언트와 캐시는 여러 스레드에서 공유할 수 있습니다. 네트워크 호출은 동기식이므로 코루틴에서는 `Dispatchers.IO` 같은 적절한 실행 환경에서 호출하세요. `AssignmentOutcome`을 명시적으로 전달하거나 `trackIfAssigned`를 사용하면 ThreadLocal 전파가 필요하지 않습니다.

## 업그레이드

API/스키마를 먼저 업그레이드한 다음 SDK/스타터 소비자를 다시 빌드하세요. 새 읽기 전용 조회 경로가 없는 구버전 서버에서 `trackIfAssigned`의 캐시 미스는 실패합니다.

[이슈 #27 결정과 마이그레이션](../docs/issue-27.md), [Spring Boot 통합](../prism-spring-boot-starter/README.md)

```bash
./gradlew :prism-sdk:test
./gradlew :prism-sdk:verifySdkPublication
```

`verifySdkPublication`은 SDK와 공용 라이브러리를 `prism-sdk/build/publication-test/repository`에 게시하고, 독립 Java 프로젝트에서 Gradle 모듈 메타데이터와 POM을 각각 사용해 컴파일·실행합니다. 할당·캐시·전환 호출까지 검사하며 `check`와 `build`에도 포함됩니다.

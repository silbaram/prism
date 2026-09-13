# Prism SDK

JDK 21 이상에서 사용하는 Java/Kotlin SDK입니다. 기본 `LOCAL` 모드는 활성 실험 설정을 받아 `prism-core`로 할당과 SpEL 타기팅을 평가합니다. 첫 설정 수신 이후 `assign()`은 HTTP 요청 없이 동작하며, 노출·전환 이벤트는 메모리 큐에 넣어 배치 전송합니다.

Spring Boot 애플리케이션 컨텍스트 없이 사용할 수 있습니다. SDK는 core와 `spring-expression`을 전이 의존하며, Gradle 모듈 메타데이터와 Maven POM 모두 해석된 버전을 제공합니다.

## 시작하기

```kotlin
implementation("io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT")
```

```kotlin
import io.github.silbaram.prism.sdk.*

val client = PrismClient("http://localhost:8080")
val experiments = PrismExperimentClient(client)

// 실험 경험을 제공하는 시점: 로컬 할당 + 노출 이벤트 큐 등록
val outcome = experiments.assign("user-123", "checkout", mapOf("age" to 25, "country" to "KR"))
val variant = if (outcome.assigned) outcome.variant else "control"

// 구매 성공 시: true는 로컬 큐에 등록됐다는 뜻입니다.
val queued = experiments.trackIfAssigned("user-123", "checkout", "purchase")

// 애플리케이션 종료 시 호출. 애플리케이션 실행 중에는 같은 client를 공유합니다.
client.close()
```

타기팅 규칙은 설정 서버에서 내려받아 실행하므로 신뢰하는 Prism 서버 URL을 사용하세요. 사용자 속성은 로컬 평가에만 사용되며 설정 요청이나 이벤트 payload에 포함하지 않습니다.

## 평가와 실제 노출을 나누기

화면을 준비할 때 평가하고, 실제로 보여줄 때 노출을 기록할 수 있습니다.

```kotlin
val assignment = client.evaluate("user-123", "checkout", mapOf("age" to 25, "country" to "KR"))
// assignment.variant에 맞는 경험을 실제 제공한 시점에만 호출
val exposed = client.recordExposure(assignment)
if (exposed) {
    val queued = client.trackConversion("user-123", "checkout", "purchase")
}
```

`evaluate()`는 노출을 만들지 않습니다. `assign()`은 평가와 노출 큐 등록을 함께 수행하며, 반복 호출은 각각 새 노출 이벤트를 생성합니다. 동일 이벤트의 재전송만 event ID로 중복 제거합니다. 사용자×실험 단위 노출 억제는 Phase 2 후속 범위입니다.

특정 노출에 전환을 연결하려면 `experiments.track(outcome, "purchase")`를 사용하세요. `assign()` 결과의 `exposureEventId`와 `configVersion`을 유지하므로, 이후 같은 사용자가 다른 변형에 배정되어도 원래 노출에 귀속됩니다. `PrismClient`를 직접 사용하면 `trackConversion(assignment, eventName)`에 `assign()` 결과를 전달합니다. 순수 `evaluate()` 결과에는 노출 ID가 없어 이 방식으로 추적할 수 없습니다. `trackIfAssigned(userId, key, eventName)`은 최근 노출을 조회하는 별도 계약입니다.

## 설정과 실패 처리

```kotlin
val client = PrismClient(
    "http://localhost:8080",
    options = PrismClientOptions(
        configSyncInterval = java.time.Duration.ofSeconds(60),
        initializationTimeout = java.time.Duration.ofSeconds(5),
        eventFlushInterval = java.time.Duration.ofSeconds(5),
        eventBatchSize = 100,
        eventQueueCapacity = 10_000,
        exposureCacheMaximumSize = 10_000,
        shutdownTimeout = java.time.Duration.ofSeconds(5)
    )
)
```

첫 설정 수신 전 평가는 `initializationTimeout`까지 기다린 뒤 실패 결과를 반환합니다. `Duration.ZERO`면 기다리지 않습니다. 동기화 실패·잘못된 설정은 마지막 정상 설정을 보존하며, 정상적인 빈 설정은 기존 활성 실험을 모두 제거합니다. 설정 변경은 서버 캐시 TTL(기본 5초)과 SDK 폴링 주기 내에 반영되므로 즉시 중단 스위치로 사용할 수 없습니다. 연결이 끊긴 동안 마지막 정상 설정에는 만료 시간이 없습니다.

메모리 큐가 가득 차면 새 이벤트를 거절합니다. `assign()`도 노출을 큐에 넣지 못하면 실패를 반환하므로 기본 경험으로 처리할 수 있습니다. 재시도는 주기적인 배치 전송에서 동일한 ID와 payload로 이루어집니다. 일부 이벤트의 영구 거부는 로그를 남기고 해당 이벤트를 제거하며, 거부된 노출을 참조하는 대기 전환도 제거합니다.

`flush()`는 진입 시 큐의 이벤트를 제한 시간 내에 전송합니다. 응답 유실·HTTP 오류·불완전한 ACK에는 큐를 보존하고 `false`를 반환합니다. `true`는 해당 flush가 거부 없이 완료되고 큐가 비었다는 뜻이며, 이전의 비동기 전송에서 거부된 이벤트까지 성공했다는 의미는 아닙니다. `pendingEventCount`로 현재 미확인 이벤트 수를 확인할 수 있습니다.

개별 이벤트의 `RETRY`는 해당 ID만 보존하며 뒤의 정상 배치는 계속 전송합니다. 각 flush는 시작 시점의 이벤트를 한 번씩 시도합니다. 최근 거부된 노출 ID는 큐 용량 한도 내에서 기억하여 명시적 참조로 다시 추적하는 것도 거부합니다.

`close()`와 JVM 종료 훅은 `shutdownTimeout` 안에 남은 이벤트 전송을 시도합니다. 동시 종료 호출은 먼저 시작한 종료를 제한 시간 내에서 기다리며, HTTP 클라이언트는 마지막 전송 시도가 끝난 뒤 정리합니다. 비정상 종료나 네트워크 단절이 계속되면 메모리 이벤트가 유실될 수 있습니다. 디스크 기반 재전송은 제공하지 않습니다. LOCAL·REMOTE HTTP 타임아웃은 응답 본문 수신까지 포함합니다.

## 전환 귀속과 프로세스 경계

로컬 모드의 전환은 기록하거나 읽기 전용 조회로 확인한 최근 노출의 `exposureEventId`를 참조합니다. 전환 시점에 실험을 새로 할당하지 않습니다. 서버는 노출의 사용자·실험·변형·설정 버전이 일치하는지 확인하고, 노출이 아직 도착하지 않았다면 재시도를 요청합니다. 설정 변경 전의 버퍼 이벤트도 재평가하지 않고 원래 변형으로 기록합니다.

다른 요청·스레드에서 같은 client를 공유하면 추적할 수 있습니다. 다른 프로세스·재시작·캐시 만료·퇴출로 참조가 없으면 `GET /v1/assignments?order=OCCURRED_AT`에서 이미 커밋된 노출의 이벤트 ID와 설정 버전을 조회합니다. 배치 도착 순서 대신 발생 시각을 비교하며, 같은 마이크로초의 노출은 이벤트 ID 역순으로 선택합니다. SDK 호스트의 시계 동기화가 필요합니다. 원래 노출이 다른 프로세스의 큐에만 있거나 조회된 구버전 로그에 이벤트 ID가 없으면 전환은 false를 반환합니다. 구버전 노출을 사용하는 흐름은 `REMOTE` 모드를 유지하세요. 조회가 필요한 전환 호출에는 HTTP 대기가 발생할 수 있습니다.

노출 ID가 포함된 할당 결과를 다른 인스턴스에 명시적으로 전달하면, 원래 노출이 아직 전송되지 않았어도 전환을 큐에 넣을 수 있습니다. 수집 서버는 노출 도착 전에는 `RETRY`를 반환하고, 도착 후 같은 전환 ID를 한 번만 저장합니다.

성공한 노출 참조는 최대 10,000개 캐시하며, 기본 TTL은 30초입니다. `PrismExperimentClient`의 `assignmentCacheTtl`은 내부 노출 참조 조회에도 적용됩니다. `PrismClient`를 직접 사용하면 `getAssignment`와 `trackConversion`의 `exposureCacheTtl` 인자로 조정할 수 있습니다. TTL이 0이면 서버에서 매번 조회합니다. 단, 같은 client에서 아직 전송 중인 로컬 노출은 TTL·캐시 퇴출과 관계없이 ACK까지 보존하여 전환을 연결합니다. `assignmentCacheMaximumSize`는 래퍼 캐시 크기이며, 평가 결과를 캐시해 설정 변경을 무시하는 기능은 아닙니다. 동시 조회는 진행 중인 요청을 공유하고, HTTP 조회 중에도 로컬 할당은 진행할 수 있습니다.

## 기존 원격 모드

```kotlin
val client = PrismClient(
    "http://localhost:8080",
    options = PrismClientOptions(evaluationMode = EvaluationMode.REMOTE)
)
```

`REMOTE`는 기존 `GET /v1/assign`, `GET /v1/assignments`, `POST /v1/conversions`를 사용합니다. 할당은 노출 저장 후 반환하고, 전환 `true`는 서버가 수락했다는 뜻입니다. 원격 전환은 자동 재시도하지 않습니다. 사용자 속성을 전달한 원격 할당은 지원하지 않아 실패를 반환합니다. `evaluate()`와 `recordExposure()`는 로컬 전용입니다.

기존 두 API(`/v1/assign`, `/v1/conversions`)는 deprecated 상태로 하위 호환을 유지합니다. 구버전 API에 새 SDK를 연결할 때는 `REMOTE`를 명시해야 합니다.

## 지표 해석

Admin CVR은 **목표 이벤트 발생 고유 사용자 / 노출 고유 사용자 × 100**입니다. `page_viewed`, `payment_failed` 등의 보조 이벤트도 저장할 수 있으나 목표와 다른 이름을 사용하세요. 보조 이벤트는 별도 이벤트 화면에서 확인합니다. 목표 미설정 또는 노출 0명일 때는 CVR이 표시되지 않습니다.

## 업그레이드와 검증

[Phase 1 API 계약·마이그레이션](../docs/issue-28-phase-1.md)에 따라 스키마와 API를 먼저 배포한 뒤 SDK/스타터 소비자를 다시 빌드하세요. 기본 모드와 전환 Boolean의 의미가 바뀌므로 기존 애플리케이션은 `REMOTE`를 명시하거나 로컬 동작에 맞게 이전해야 합니다.

```bash
./gradlew :prism-sdk:test
./gradlew :prism-sdk:verifySdkPublication
```

`verifySdkPublication`은 common·core·SDK를 빌드 디렉터리의 임시 Maven 저장소에 게시하고, 독립 Java 프로젝트가 Gradle 모듈 메타데이터와 POM으로 각각 로컬 할당·SpEL 타기팅·이벤트 배치를 실행하는지 검증합니다. `check`와 `build`에 포함됩니다.

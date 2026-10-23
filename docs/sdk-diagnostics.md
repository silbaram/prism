# SDK 진단과 설정 신선도 정책

기본 JVM SDK에서 Spring 없이 설정 동기화와 로컬 이벤트 전송을 관측한다. SDK/스타터 소비자는 재컴파일해야 한다. 서버 API·DB 스키마 변경은 없다.

## 설정 예제

```kotlin
val client = PrismClient(
    "https://prism.example.com",
    options = PrismClientOptions(
        apiKey = requireNotNull(System.getenv("PRISM_CLIENT_API_KEY")),
        configSyncInterval = java.time.Duration.ofSeconds(30),
        maxConfigAge = java.time.Duration.ofMinutes(5),
        staleConfigPolicy = StaleConfigPolicy.BLOCK_NEW_ASSIGNMENTS
    )
)
val diagnostics = client.diagnostics
val config = diagnostics.configuration
val events = diagnostics.events
```

5분은 예시이며 운영 전파 목표에 맞춰 결정한다. `maxConfigAge`는 양수이며 나노초로 표현 가능한 Duration이어야 한다. `BLOCK_NEW_ASSIGNMENTS`에는 반드시 기한을 지정한다. 동기화 주기와 요청 지연을 고려해 기한을 설정한다. 정상적으로 통신 중이어도 서버 캐시 때문에 관리자의 변경을 즉시 확인하는 것은 아니다.

Spring 스타터는 같은 옵션을 제공한다.

```yaml
prism:
  client:
    url: https://prism.example.com
    config-sync-interval: 30s
    max-config-age: 5m
    stale-config-policy: BLOCK_NEW_ASSIGNMENTS
```

API 키는 기존 `prism.client.api-key` 설정과 안전한 비밀 값 주입 경로를 사용한다. Java 소비자는 `client.getDiagnostics()`로 동일한 불변 객체와 getter에 접근한다.

## 기본값과 상태

| 설정·상태 | 동작 |
|---|---|
| 기본값: `maxConfigAge=null`, `KEEP_LAST` | 이전과 같이 마지막 정상 설정을 유지. 초기화 후 진단 상태는 `UNBOUNDED` |
| 기한 지정 + `KEEP_LAST` | 만료를 `STALE`로 관측하되 기존 설정으로 평가 |
| 기한 지정 + `BLOCK_NEW_ASSIGNMENTS` | 기한 이상 경과하면 새 배정·노출을 차단 |
| `NOT_INITIALIZED` | 검증된 설정 없음. age·version·lastSuccessAt은 null |
| `FRESH` | 지정한 기한 미만의 설정. 모든 실험의 규칙 지원이나 이벤트 전송 정상까지 의미하지 않음 |
| `STALE` | 마지막 정상 설정 확인으로부터 기한 이상 경과 |
| REMOTE 모드 | configuration·events·exposureDedup은 null. 로컬 상태를 정상적인 0으로 표시하지 않음 |

설정 상태와 `lastSyncResult`는 별개다. 최근 동기화가 실패해도 기한 전에는 `FRESH`일 수 있다. 기한 없는 `UNBOUNDED`도 최근 서버 연결이 정상이라는 뜻은 아니다. `closed`는 종료 여부이며 위 설정 상태와 별도로 확인한다.

설정 age와 큐 대기 시간은 단조 증가 시계로 계산한다. UTC 시각 필드는 관측용이며 시스템 시계가 뒤로 움직여도 설정 수명이 늘어나지 않는다. 정상 설정 응답과 해당 설정을 확인하는 조건부 `304`만 age를 초기화한다. 잘못된 JSON·구조·구버전 revision·HTTP 오류·타임아웃은 초기화하지 않는다. 유효한 빈 활성 목록은 정상 설정으로 반영한다.

SSE의 검증된 config 프레임도 신선도를 갱신한다. 주석 heartbeat·부분 프레임·연결 성공 자체는 갱신하지 않는다. 폴링은 계속 실행되며, 오래된 조건부 요청의 `304`가 더 새로운 SSE 설정을 확인한 것으로 처리되지 않는다. SSE 연결 단절 횟수는 별도로 제공하지 않는다.

## 만료 시 배정과 귀속

- `evaluate()`·`assign()`은 `SdkResponseCode.STALE_CONFIGURATION` (`9996`)과 null 변형을 반환한다. 서비스의 기본 경험은 호출자가 결정한다.
- 미리 평가한 결과의 `recordExposure()`도 실제 등록 시 만료되었다면 false다. 모집단 노출도 false이며 `isInHoldout()`은 null이다.
- 새 호출에서 이미 알고 있던 노출을 재사용하는 경우에도 배정·노출 API의 차단 정책을 적용한다.
- 이전 정상 노출의 명시적 참조와 조회 기반 전환, 모집단 전환, 이미 큐에 있는 이벤트의 전송은 계속 가능하다. 타기팅 거절·지원하지 않는 변형·귀속 오류 같은 기존 제한은 그대로 적용한다.
- 정상 설정 또는 유효한 `304`를 받으면 자동 복구한다. 새 빈 설정이 실험을 제거하면 복구 후에도 제거된 실험을 재활성화하지 않는다.

`staleRejectedCount`는 반복 호출을 포함한 배정·노출 거절 횟수다. 고유 사용자 수가 아니며, 순수한 홀드아웃 조회에는 증가하지 않는다. 설정이 없는 초기화 실패는 기존 `9999`, 지원하지 않는 규칙은 `9997`, 노출 중복 제거 용량 초과는 `9998`로 구분한다.

## 진단 스냅샷

`SdkDiagnostics`와 하위 객체는 불변 값이다. 조회는 네트워크 요청·설정 갱신·이벤트 전송을 만들지 않는다. 설정·큐 각각의 잠금으로 읽는 관측값이며, SDK와 서버 전체를 같은 트랜잭션 시점에 캡처한 결과는 아니다. 카운터는 client 인스턴스 수명 기준으로 재시작 시 초기화한다. API 키·사용자 ID·속성·규칙 원문·예외 메시지는 스냅샷에 넣지 않는다.

| 구성 | 주요 필드 |
|---|---|
| `configuration` | status, version, revision, age, maxAge, stalePolicy, lastAttemptAt, lastSuccessAt, lastSyncResult, consecutiveFailureCount, lastFailureAt, lastFailure, lastFailureHttpStatus, targetingRejectedExperimentCount, staleRejectedCount |
| `events` | pendingCount, capacity, oldestPendingAge, enqueuedCount, capacityRejectedCount, acceptedCount, duplicateCount, durablyQueuedCount, retryResponseCount, rejectedCount, discardedDependentCount, acknowledgedBatchCount, failedBatchCount, lastBatchAttemptAt, lastBatchResponseAt, lastBatchStatus, lastFailureAt, lastFailure, lastFailureHttpStatus |
| `exposureDedup` | experimentCount, populationCount, capacityPerScope, experimentRejectedCount, populationRejectedCount |

`lastFailureAt`·`lastFailure`는 가장 최근의 실패 이력으로 복구 후에도 남는다. 현재 동기화 결과와 연속 실패 수를 함께 확인한다. 실패 종류는 고정된 enum이며 HTTP 오류, 응답 형식 오류, 구버전 revision, 타임아웃, 인터럽트, 전송 오류를 구분한다. HTTP 실패의 상태 코드는 `lastFailureHttpStatus`로 확인하며 다른 종류의 실패이면 null이다. 비동기 예외로 감싸진 타임아웃도 TIMEOUT으로 분류한다. 설정 진단의 동기화 시도는 폴링과 수신한 config 프레임을 대상으로 한다.

## 이벤트 상태를 읽는 방법

| 필드 | 의미 |
|---|---|
| enqueuedCount | 로컬 큐에 새로 등록한 이벤트 수. 서버 저장 성공 수가 아님 |
| capacityRejectedCount | 큐가 가득 차 등록을 거절한 호출 수. 종료 후 호출·잘못된 입력은 제외 |
| acceptedCount / duplicateCount | 완전하고 검증된 서버 ACK의 ACCEPTED / DUPLICATE 응답 수 |
| durablyQueuedCount | Kafka 경로의 QUEUED 응답 수. DB 반영이나 최종 귀속 검증 완료를 의미하지 않음 |
| retryResponseCount | RETRY 응답 수. 같은 ID의 재시도 응답도 반복 집계 |
| rejectedCount | 서버가 영구 거절한 ACK 응답 수 |
| discardedDependentCount | 노출 거절 때문에 SDK 큐에서 제거한 종속 전환 수. 서버 거절 수와 구분 |
| acknowledgedBatchCount | 전체 이벤트 ID·개수를 검증한 응답 배치 수. RETRY·REJECTED가 포함될 수 있음 |
| failedBatchCount | HTTP·타임아웃·잘못된 ACK 등 배치 요청 실패 수. 빈 flush·flush 잠금 대기는 제외 |

큐의 oldestPendingAge는 최초 큐 등록 이후 대기 시간이다. 재시도·서버 조회·설정 갱신은 대기 시간을 초기화하지 않는다. 비어 있으면 null이다. 상태별 응답 카운터와 종속 전환 폐기는 다른 집계이므로 이를 더해 고유 행동 수로 해석하지 않는다.

전송 중에는 `lastBatchStatus=IN_FLIGHT`, 완전한 응답이면 `ACKNOWLEDGED`, 요청 실패이면 `FAILED`다. 빈 `flush()`는 마지막 배치 상태를 바꾸지 않는다. ACKNOWLEDGED도 전부 저장됐다는 뜻은 아니며 이벤트 상태 카운터와 pendingCount를 함께 본다. 잘못된 ACK는 성공 수치를 늘리지 않고 원래 이벤트 ID와 큐를 보존한다.

`close()` 후에도 스냅샷을 조회할 수 있다. 남은 이벤트가 있다면 closed=true와 pendingCount로 확인한다. 메모리 큐이므로 프로세스 종료 후 복구되지는 않는다. 서버의 inbox/outbox backlog·DLQ 처리 결과, 관리자 통합 화면, 중앙 진단 수집, 영속 큐는 후속 개발 범위다.

## 구현 검증

2026-10-19, `feature/sdk-diagnostics-config-freshness`의 로컬 변경 기준으로 다음 명령을 실행했다.

```bash
./gradlew test :prism-sdk:verifySdkPublication :prism-api:bootJar :prism-admin:bootJar --offline --max-workers=4
```

- 테스트 363개 통과, 실패·오류·건너뜀 0건. 새 SDK 장애·동시성 테스트 20개와 스타터 설정 테스트를 포함한다.
- 기본 SDK·SpEL 호환 SDK를 Gradle 모듈 메타데이터·Maven POM으로 사용하는 독립 Java 소비자 4가지 검증 통과. 기본 SDK의 Spring 없는 런타임 경계와 공개 진단 getter도 확인했다.
- API/Admin 실행 JAR 빌드 통과. 추가 리뷰에서는 비동기 HTTP 예외로 감싸진 타임아웃의 오분류를 재현하고 수정했다.

위 일반 `test` 명령에는 실제 MySQL/Kafka 검증과 운영 부하 검증이 포함되지 않는다. QUEUED·RETRY 등 SDK 응답 처리는 테스트 서버로 재현했다. 이후 실제 MySQL·Kafka 테스트 5건과 실제 API 중단·SDK 만료·복구·DB 반영도 추가 확인했다. 진단에서 발견된 Kafka 종료 처리 문제도 수정하고 API 테스트 44건·실제 Kafka 테스트 2건으로 재검증했다. 범위와 수정 결과는 [추가 진단 결과](sdk-runtime-diagnostics-2026-10-19.md)에 기록했다.

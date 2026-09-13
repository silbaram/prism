# #28 Phase 1 — 로컬 평가와 배치 이벤트

## 구현 범위

- 활성 실험 설정 배포 `GET /v1/config`: 내용 기반 SHA-256 버전, ETag/304, 서버 Caffeine 캐시.
- SDK 기본 LOCAL 평가: core의 기존 해시·SpEL 규칙을 재사용하고, 사용자 속성은 서버로 보내지 않음.
- `POST /v1/events`: 노출·전환 배치, 전역 event ID 멱등성, 개별 ACK, 정확한 선행 노출 참조.
- 설정 동기화와 이벤트 전송은 별도 백그라운드 스레드. 최초 설정 대기, 큐 한도, 종료 전송 시간 설정.
- Spring 스타터 속성 바인딩과 컨텍스트 종료 시 client.close().
- 기존 원격 API와 `EvaluationMode.REMOTE` 유지. 새 SDK의 기본 모드는 LOCAL이므로 소비자 재빌드와 모드 검토 필요.

## 설정 API

```http
GET /v1/config
If-None-Match: "<previous-version>"
```

```json
{
  "version": "<64-character-sha256>",
  "experiments": [{
    "key": "checkout",
    "status": "ACTIVE",
    "goalEventName": "purchase",
    "variants": [{"name": "control", "weight": 50}, {"name": "B", "weight": 50}],
    "targetingRules": ["age >= 20 && country == 'KR'"]
  }]
}
```

ACTIVE만 반환합니다. 실험은 키 순서, variants와 targetingRules는 DB ID 순서로 읽어 원격·로컬 해시 구간이 일치하도록 합니다. 버전은 설정 내용의 해시이며 수정 시각에 의존하지 않습니다. ETag가 같으면 304와 빈 본문을 반환합니다. 서버 캐시 TTL은 `prism.config.cache-ttl`(기본 5초)이며, Admin이 별도 프로세스여도 TTL 만료 후 변경을 읽습니다.

Admin 생성·수정·활성화, 설정 API, SDK가 키·변형 이름과 가중치 검증 기준을 공유합니다. 이름은 1–255자이고 공백만으로 구성할 수 없으며, 한 실험의 변형 이름은 중복될 수 없습니다. 검증 도입 전에 저장된 잘못된 ACTIVE 실험은 설정 API에서 제외하고 서버 로그에 ID와 이유를 남깁니다. 정상 실험의 초기화·변경·중지는 계속 반영되며, 해당 실험을 수정하면 다음 설정 응답에 다시 포함됩니다.

SDK는 기본 60초마다 조건부 요청합니다. 실패하거나 잘못된 응답이면 마지막 정상 설정을 계속 사용합니다. 정상 빈 목록은 현재 실험을 모두 제거합니다. 최초 정상 설정 전에는 최대 5초 대기하며 `initializationTimeout=Duration.ZERO`로 대기를 끌 수 있습니다. 이 시간은 초기 평가 호출의 대기 한도이며 HTTP 요청 타임아웃은 별도입니다. 설정 동기화가 계속 실패하면 오래된 설정으로도 평가하므로 실험 중지가 즉시 전파되는 계약은 아닙니다.

core의 Spring Boot 플러그인은 제거했습니다. 루트가 모든 라이브러리에 이미 Spring Boot BOM을 명시적으로 import하므로 의존성 버전 관리는 유지됩니다. SDK 소비자는 Spring Boot를 시작할 필요가 없지만 `spring-expression`과 그 전이 라이브러리를 함께 사용합니다.

## 노출과 전환

`assign()`은 로컬 평가 후 노출 이벤트를 큐에 등록합니다. 기존 어노테이션·전략 경로에서도 이 동작을 사용합니다. 화면 준비와 실제 노출 시점이 다르면 `evaluate()`로 먼저 계산하고 실제 경험을 제공한 시점에 `recordExposure(result)`를 호출합니다. 반복 assign은 별도 노출 이벤트이며, 사용자별 노출 억제는 Phase 2 범위입니다.

```json
{
  "events": [
    {"eventId":"<uuid-1>", "type":"exposure", "userId":"u", "experimentKey":"checkout",
     "variant":"B", "timestamp":"2026-09-13T02:00:00Z", "configVersion":"<64-character-sha256>"},
    {"eventId":"<uuid-2>", "type":"conversion", "userId":"u", "experimentKey":"checkout",
     "variant":"B", "eventName":"purchase", "exposureEventId":"<uuid-1>",
     "timestamp":"2026-09-13T02:01:00Z", "configVersion":"<64-character-sha256>"}
  ]
}
```

요청당 1–1,000개 이벤트를 받으며, 한 요청 안에서 eventId는 중복될 수 없습니다.

이벤트 ID와 노출 참조는 소문자 표준 UUID, 버전은 소문자 64자리 해시입니다. 사용자·실험·변형·이벤트 이름은 공백이 아닌 1–255자이며 대소문자와 후행 공백을 구분합니다. timestamp는 ISO-8601 Instant로 수신해 UTC로 저장하며 기존 MySQL TIMESTAMP 범위를 검증합니다.

로그 엔티티의 `LocalDateTime`은 UTC 값입니다. 전용 JPA 변환기가 이를 `Timestamp`의 실제 시각으로 변환하며, 기존 원격 경로에서 생성하는 로그도 UTC를 사용합니다. 기본 JDBC URL은 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true`로 세션과 드라이버를 맞춥니다. 사용자 지정 URL에도 이 옵션을 반영하세요. 설정 근거는 [Connector/J의 시각 보존 문서](https://dev.mysql.com/doc/connector-j/en/connector-j-time-instants.html)에 있습니다.

029 마이그레이션은 로그 timestamp를 `TIMESTAMP(6)`으로 변경하고 발생 시각 조회용 인덱스를 추가합니다. LOCAL SDK의 노출 조회는 `GET /v1/assignments?order=OCCURRED_AT`을 사용하여 발생 시각 역순, 동률이면 이벤트 ID 역순으로 선택합니다. 이에 따라 늦게 도착한 과거 배치가 최신 노출을 대체하지 않습니다. 저장 정밀도는 마이크로초이며, 호스트 간 발생 시각 비교에는 시계 동기화가 필요합니다. 기존 조회의 기본값 `order=RECORDED`와 REMOTE 전환은 DB 저장 순서 기준을 유지합니다.

전환은 명시한 노출의 사용자·실험·변형·설정 버전과 일치해야 합니다. 같은 배치에서는 노출부터 처리하고, 배치가 달라 노출이 아직 없으면 RETRY를 반환합니다. 이벤트 도착 순서나 API 서버 시계로 다른 노출에 귀속시키지 않습니다. 버퍼에 남은 과거 설정의 이벤트는 현재 가중치나 상태로 다시 평가하지 않습니다. 노출의 실험 키는 서버에 존재해야 하며, 보고된 변형은 SDK가 평가한 값으로 취급합니다. 인증·설정 서명 검증은 이 단계에 추가하지 않습니다.

로컬 노출 참조가 만료·퇴출되었거나 다른 인스턴스에서 전환이 발생하면 SDK는 이미 저장된 노출을 조회합니다. 이 응답에 `exposureEventId`와 `configVersion`을 추가했습니다. 조회는 새 노출을 만들지 않고 성공한 참조만 저장합니다. 원래 노출이 다른 인스턴스의 메모리 큐에만 있거나 조회된 구버전 노출에 event ID가 없으면 false를 반환합니다. 구버전 로그를 사용하는 기존 흐름은 REMOTE 모드로 유지할 수 있습니다.

LOCAL `assign()`과 `AssignmentOutcome`도 노출 ID·설정 버전을 반환합니다. `track(outcome, eventName)` 또는 `trackConversion(assignment, eventName)`은 이 참조를 그대로 사용하므로 재할당·캐시 퇴출·인스턴스 이동으로 귀속이 바뀌지 않습니다. 명시적 참조를 전달한 다른 인스턴스는 노출이 아직 도착하지 않았어도 전환을 큐에 넣어 재시도할 수 있습니다. `trackIfAssigned`는 최근 노출 조회를 사용하는 경로이며, 노출 ID가 없는 순수 evaluate 결과는 명시적 전환 추적에 사용할 수 없습니다.

## ACK와 멱등성

각 이벤트는 독립 트랜잭션으로 receipt와 로그를 함께 커밋합니다. 응답 results는 요청 순서입니다.

| status | 의미 | SDK 처리 |
|---|---|---|
| ACCEPTED | 이번 요청에서 커밋됨 | 큐에서 제거 |
| DUPLICATE | 동일 ID·payload가 이미 커밋됨 | 큐에서 제거 |
| REJECTED | 잘못된 필드, ID 재사용, 노출 귀속 불일치 등 | 제거하고 로그 기록 |
| RETRY | 선행 노출 미도착 또는 일시적 저장 실패 | 동일 ID로 재전송 |

참조 ID가 이미 전환 이벤트로 커밋되어 노출이 될 수 없는 경우에는 REJECTED를 반환합니다. 개별 RETRY 때문에 뒤의 정상 배치가 막히지 않도록, SDK는 flush 시작 시점의 이벤트 ID를 한 번씩 시도합니다.

`event_receipts`의 PK는 노출·전환을 통틀어 하나의 ID를 보장합니다. 동일 ID에 다른 payload는 거부합니다. 각 로그의 nullable event_id 유니크 인덱스도 유지하고, 구버전 로그는 NULL로 남깁니다. receipt는 로그와 함께 보존해야 멱등성과 설정 버전 조회가 유지됩니다. 보존 기간·자동 정리는 후속 정책입니다.

기존 Admin 집계는 새 로그를 그대로 사용합니다. 새 전환의 impression_id를 실제 노출에 연결하므로 사용자 단위 CVR과 목표 이벤트 필터도 유지됩니다.

## 큐와 종료 정책

- 기본 배치 크기 100, 큐 최대 10,000, 전송 주기 5초. 크기 또는 주기로 전송하며 HTTP 실패/응답 유실은 원래 ID로 재시도합니다.
- 큐 포화 시 새 이벤트는 false, 노출을 등록하지 못한 assign은 실패 결과입니다. 이미 대기 중인 이벤트를 밀어내지 않습니다.
- LOCAL 전환의 true는 메모리 큐 등록 여부입니다. REMOTE의 true는 기존과 같이 서버 수락 여부입니다.
- `flush()`는 진입 시점의 이벤트를 종료 타임아웃 안에 비우려 시도합니다. 불완전한 ACK에는 이벤트를 제거하지 않습니다.
- `close()`/JVM 종료 훅은 기본 총 5초 내에 전송을 시도합니다. 동시 종료는 먼저 시작한 종료의 완료를 제한 시간 내에서 기다리고, HTTP 클라이언트는 전송 시도 후 한 번만 정리합니다. 지속 장애·강제 종료 시 유실될 수 있으며 디스크 버퍼는 구현하지 않았습니다.
- 노출 참조 캐시는 최대 10,000개, 기본 TTL은 30초입니다. 래퍼의 `assignmentCacheTtl`을 내부 조회까지 적용하며, 직접 호출은 `getAssignment`/`trackConversion`의 `exposureCacheTtl`로 조정합니다. TTL 0은 서버 조회 캐싱을 끕니다. 아직 전송 중인 로컬 노출은 큐의 ACK까지 별도 보존합니다.
- 조회가 필요한 전환은 블로킹 HTTP를 수행할 수 있습니다. 래퍼는 진행 중인 조회를 공유하되 캐시 잠금 밖에서 HTTP를 수행하므로, 설정 수신 후의 로컬 할당이 전환 조회를 기다리지 않습니다.

## 배포 순서

1. DB 백업 후 API/Admin 쓰기를 중지합니다.
2. 기존 설치는 누락된 `027_metric_integrity.sql` → `028_exact_identity_and_attribution.sql` → [029_local_evaluation_events.sql](../prism-infrastructure/src/main/resources/migrations/029_local_evaluation_events.sql)을 순서대로 각각 한 번 적용합니다. 이미 028까지 적용했으면 029만 실행합니다. 신규 DB는 갱신된 schema.sql을 사용합니다.
3. API/Admin을 배포하고 사용자 지정 JDBC URL의 UTC 세션·시각 보존 옵션을 위 설정과 맞춥니다. 기존 원격 API는 계속 동작합니다.
4. SDK/스타터 소비자를 다시 컴파일합니다. 구버전 API 연결 또는 기존 동기식 응답 의미 유지가 필요하면 REMOTE를 명시합니다.
5. LOCAL 전환 후 애플리케이션 종료 시 close()를 호출하고 큐 포화·이벤트 거부 로그를 확인합니다.

## 검증

2026-09-13, JDK 21에서 반복 리뷰 후 `clean build :prism-api:mysqlTest --offline --no-daemon --max-workers=2` 통과. 일반 테스트 126개, 독립 SDK 소비자 검증 2건, MySQL 8.4.11 테스트 4건이 성공했으며 실패·건너뜀은 없습니다. 마지막 동시 종료 대기 보강 후 `build :prism-api:mysqlTest`도 다시 통과했습니다.

일반 테스트는 설정 필터·ETag·설정 변경/장애, 로컬/원격 분배 일치, SpEL 속성 전달, SDK→HTTP→JPA→집계, 인스턴스 간 노출 조회, 순서 역전, 동시 중복 전송, 잘못된 귀속, 큐 포화·재전송·종료를 검사합니다.

리뷰 회귀 테스트는 Admin의 중복 이름 거부와 기존 잘못된 실험 격리, TTL 이후 노출 갱신, TTL 0, 전송 중 노출의 캐시 퇴출, 느린 조회와 동시 로컬 할당, 지연 배치 및 동일 시각의 노출 조회를 검사합니다. MySQL 검증에는 마이크로초 정밀도 저장과 발생 시각·이벤트 ID 기준의 조회를 추가했습니다.

추가 리뷰에서는 원래 노출을 지정한 전환 귀속, 노출보다 먼저 도착하는 인스턴스 간 전환, 부분 재시도 진행, 동시 close, LOCAL·REMOTE의 응답 본문 타임아웃을 검증했습니다. 분배 엔진은 실제 `Int.MIN_VALUE` 해시 입력으로 0% 변형을 선택하지 않는지 검사합니다. MySQL 테스트는 신규·마이그레이션 각각 UTC·KST JVM에서 실행하며, `UNIX_TIMESTAMP`로 저장된 실제 시각을 확인합니다.

`verifySdkPublication`은 common/core/SDK를 로컬 임시 저장소에 게시해 별도 Java 소비자가 POM과 Gradle module metadata로 각각 로컬 평가·SpEL·배치 전송까지 실행합니다.

`mysqlTest`는 임의 이름의 테스트 DB에서 신규 스키마와 027→028→029 마이그레이션 두 경로를 두 JVM 시간대로 검증합니다. MySQL의 실제 문자열 정렬 규칙, 유니크 제약, 동시 재전송과 기존 지표도 검사합니다.

### Phase 1 완료 조건 대조

| 조건 | 검증 근거 |
|---|---|
| 설정 DTO·ACTIVE 필터·ETag·버전·서버 캐시 | `ConfigController`/`ConfigService`, `LocalEvaluationIntegrationTest`의 설정 필터·304·변경·잘못된 기존 실험 격리 |
| 설정 수신 후 네트워크 없는 할당 | `LocalEvaluationClientTest`의 반복 할당 요청 수 검사, `ExposureLookupTest`의 느린 조회 중 동시 할당 |
| 장애 시 마지막 설정 유지·초기화 정책 | `LocalEvaluationClientTest`의 503/잘못된 설정/빈 설정/초기 대기 0 및 복구 |
| core 재사용·SpEL 속성 전달 | `LocalEvaluationIntegrationTest`의 원격·로컬 분배 비교 및 SDK→HTTP→JPA 타기팅·지표 검증 |
| 배치 수집·멱등성·전환 귀속 | H2 및 MySQL의 중복·동시 재전송·순서 역전·참조 불일치 검증, 명시적 노출 참조 전달 |
| 큐 한도·주기/크기 전송·종료 | SDK의 큐 포화·부분 RETRY·응답 유실·불완전 ACK·동시 종료·응답 본문 지연 검증 |
| 스타터 속성·기존 REMOTE 경로 | `PrismAutoConfigurationTest`, 기존 Aspect/전략 테스트, `PrismClientTest`와 기존 지표 무결성 테스트 |
| 배포 스키마·외부 소비자 | MySQL 신규/마이그레이션 × UTC/KST, POM/Gradle module metadata 기반 독립 Java 소비자 실행 |

```bash
./gradlew clean build
PRISM_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/' ./gradlew :prism-api:mysqlTest
```

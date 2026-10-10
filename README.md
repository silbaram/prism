# Prism (A/B Testing System)

Prism은 무상태 트래픽 분배 엔진, 실험 관리 Admin, 설정·이벤트 API로 구성된 A/B 테스트 플랫폼입니다. 기본 SDK는 Spring 없이 동기화한 설정으로 데이터 규칙 타기팅과 MurmurHash 분배를 실행하고 노출·전환을 배치 전송합니다. 기존 SpEL 규칙은 선택적 호환 모듈이 처리합니다.

## 주요 특징
- **무상태 트래픽 분배**: 고성능 API로 사용자별 변형(variant) 할당
- **실험 관리/Admin**: 이벤트 카탈로그·Java/Kotlin 적용 가이드·목표 및 이벤트별 CVR·순서형 퍼널·사용자 여정 제공
- **Fail-safe SDK**: 설정 동기화 장애 시 마지막 정상 설정으로 평가하며, 설정이 없으면 기본 동작으로 폴백
- **Spring 통합**: `@PrismExperiment` 어노테이션과 안전한 전환 추적 래퍼 제공

## 프로젝트 구조
- **prism-core**: 프레임워크 독립 배정 엔진, 데이터 규칙·타기팅 평가 인터페이스
- **prism-targeting-spel**: 기존 SpEL 규칙용 선택적 호환 모듈
- **prism-common**: 공용 DTO 및 상수 모음 (`ResponseCode` 등)
- **prism-api**: 트래픽 분배/로그 수집 API 서비스
- **prism-admin**: 실험 생성·목표 이벤트·통계 조회용 Admin 서비스
- **prism-sdk**: Java/Kotlin 클라이언트 SDK (자세한 내용은 `prism-sdk/README.md`)
- **prism-spring-boot-starter**: Spring Boot 통합 스타터 (자세한 내용은 `prism-spring-boot-starter/README.md`)
- **prism-infrastructure**: JPA 엔티티와 스키마 정의

## 빠른 시작
```bash
# 의존성 설치 없이 Gradle 래퍼 사용을 권장합니다.
# 1) 로컬 DB (MySQL 예시)
cd docker
docker-compose up -d

# 2) 전체 빌드/테스트
cd ..
./gradlew clean build

# 3) 인증 설정 (해시는 htpasswd -nBC 12 admin 등의 결과에서 사용자명:을 제외)
export PRISM_API_KEYS="$(openssl rand -hex 32)"
export PRISM_ADMIN_USERNAME=admin
read -rsp 'Admin BCrypt hash: ' PRISM_ADMIN_PASSWORD_HASH
export PRISM_ADMIN_PASSWORD_HASH
# API/ADMIN은 위 환경변수를 설정한 별도 터미널에서 각각 실행
./gradlew :prism-api:bootRun
./gradlew :prism-admin:bootRun
```

## 빌드 및 실행
- 필수 요구사항: JDK 21+, Docker Compose(로컬 DB), Gradle 래퍼
- 빌드/테스트: `./gradlew test` 또는 `./gradlew clean build`
- Admin 자동완성 회귀 테스트(Node.js 18+): `node --test prism-admin/src/test/js/event-picker.test.cjs` (Gradle 빌드와 별도로 실행)
- 실행:
  - Admin: `./gradlew :prism-admin:bootRun` (기본 8070)
  - API: `./gradlew :prism-api:bootRun` (기본 8080 → 동시 실행 시 `application.yml` 혹은 `SERVER_PORT` 환경변수로 변경)
  - 로컬 DB: `cd docker && docker-compose up -d` (MySQL 8.0.17+, 스키마는 `prism-infrastructure` 기준)

## 포트·환경 변수 요약
- Admin 기본 포트: 8070 (`prism-admin/src/main/resources/application.yml`)
- API 기본 포트: 8080 (`prism-api/src/main/resources/application.yml`)
- 포트 변경: `SERVER_PORT=<포트>` 환경 변수로 오버라이드하거나 각 모듈 `application.yml` 수정
- DB 연결: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` 등 환경 변수로 설정 가능
- 인증 필수: `PRISM_API_KEYS`, `PRISM_ADMIN_USERNAME`, `PRISM_ADMIN_PASSWORD_HASH` (BCrypt)
- SDK 인증: 서버와 일치하는 `PRISM_CLIENT_API_KEY`를 client 옵션/스타터에 전달
- 프로필: `SPRING_PROFILES_ACTIVE=local` 등으로 환경 분리

## 문서

- [SDK 상세 사용법](prism-sdk/README.md)
- [Spring Boot 통합 가이드](prism-spring-boot-starter/README.md)
- [DB 업그레이드](#db-업그레이드), [인증](#인증), [SDK 운영 계약](#sdk-운영-계약)
- [레이어와 홀드아웃](#레이어와-홀드아웃), [이벤트 수집과 설정 전파](#이벤트-수집과-설정-전파)
- [고급 분석](#고급-분석), [순서형 퍼널](#순서형-퍼널), [사용자 여정](#사용자-여정)

## 전환 지표

실험별 목표 이벤트를 설정하면 **목표 이벤트 발생 고유 사용자 / 노출 고유 사용자**로 CVR을 계산합니다.
반복 이벤트는 사용자별 한 번만 집계하며, 보조/실패 이벤트의 고유 사용자·발생 횟수·CVR은 이벤트별 집계에서 확인합니다.
선행 노출 없는 전환은 서버에서 `IMPRESSION_NOT_FOUND (9100)`로 거부합니다.
자동 승자 표기 대신 표본 수와 Wilson 95% 신뢰구간을 표시합니다.
SRM과 변형 간 중복 노출을 검사하며, 검사를 통과한 종료 실험에 단일 전체 전환율 검정을 제공합니다.
실험 시작 이후에는 배정 설정과 목표 이벤트를 잠그고 변경 이력을 보존합니다.
목표 미설정 기존 실험은 CVR을 표시하지 않습니다. 이미 시작한 실험의 목표가 없으면 새 실험을 생성하세요.

SRM은 Pearson 카이제곱 검정으로 배정 비율을 확인하며 `p < 0.0005`이면 경고합니다. 기대 사용자 수가 하나라도 5 미만이면 표본 부족으로 표시합니다. 종료 실험의 전체 전환율 검정은 SRM 통과와 각 변형의 기대 전환·미전환 사용자 수 5 이상을 요구합니다. 이 검정은 반복 조회·선택적 종료·여러 실험의 다중 비교를 보정하지 않으므로 수집 종료 시점을 사전에 정해야 합니다.

## DB 업그레이드

신규 DB는 [schema.sql](prism-infrastructure/src/main/resources/schema.sql)을 사용합니다. 기존 DB에는 초기화 SQL을 다시 실행하지 않으며 자동 마이그레이션도 없습니다. 기존 Docker 볼륨에도 스키마 변경이 자동 적용되지 않습니다.

기존 설치는 MySQL 8.0.17+에서 API/Admin 쓰기를 중단하고 백업한 뒤, 아직 적용하지 않은 아래 SQL을 번호순으로 각각 한 번 실행합니다.

| 순서 | SQL | 주요 변경 |
|---|---|---|
| 027 | [027_metric_integrity.sql](prism-infrastructure/src/main/resources/migrations/027_metric_integrity.sql) | 목표 이벤트·전환 귀속 제약·미귀속 로그 아카이브 |
| 028 | [028_exact_identity_and_attribution.sql](prism-infrastructure/src/main/resources/migrations/028_exact_identity_and_attribution.sql) | 식별자 비교 규칙·노출 참조 |
| 029 | [029_local_evaluation_events.sql](prism-infrastructure/src/main/resources/migrations/029_local_evaluation_events.sql) | 마이크로초 시각·이벤트 ID·영수증 |
| 030 | [030_experiment_reliability.sql](prism-infrastructure/src/main/resources/migrations/030_experiment_reliability.sql) | 설정 잠금·감사 이력 |
| 031 | [031_experiment_operations.sql](prism-infrastructure/src/main/resources/migrations/031_experiment_operations.sql) | 참여 비율·기간·가드레일 |
| 032 | [032_experiment_scale.sql](prism-infrastructure/src/main/resources/migrations/032_experiment_scale.sql) | 레이어·홀드아웃·배정 유지·이벤트 파이프라인 |
| 033 | [033_advanced_analysis.sql](prism-infrastructure/src/main/resources/migrations/033_advanced_analysis.sql) | 고급 분석 계획·사용자별 관측 |
| 034 | [034_analysis_finalization_retry.sql](prism-infrastructure/src/main/resources/migrations/034_analysis_finalization_retry.sql) | 분석 결과 확정 재시도 |
| 035 | [035_event_catalog.sql](prism-infrastructure/src/main/resources/migrations/035_event_catalog.sql) | 이벤트 카탈로그·이벤트 이름 조회 인덱스 |
| 036 | [036_user_journeys.sql](prism-infrastructure/src/main/resources/migrations/036_user_journeys.sql) | 사용자·변형·시각별 여정 조회 인덱스 |
| 037 | [037_saved_funnels.sql](prism-infrastructure/src/main/resources/migrations/037_saved_funnels.sql) | 실험별 저장된 퍼널과 수정 충돌 방지 버전 |

DDL은 암묵적으로 커밋됩니다. 롤백은 쓰기를 중단한 상태에서 백업 복원과 이전 애플리케이션 배포를 함께 수행합니다. 027의 `log_conversion_unattributed_archive`와 기존 영수증은 자동 삭제하지 않습니다. 과거 전환의 노출 참조나 실험 목표를 임의로 채우지 않으며, 로그 정리 시 전환의 노출 외래키와 중복 제거 보존 기간을 함께 고려합니다.

로그의 `LocalDateTime`은 UTC 값입니다. 사용자 지정 JDBC URL에도 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true`를 반영합니다. 식별자는 대소문자·악센트·후행 공백을 구분하고 저장 시각은 마이크로초 정밀도를 사용합니다.

DB 변경 후 API/Admin과 Kafka worker를 함께 갱신합니다. SDK/스타터 소비자를 다시 컴파일하고, 모든 수신 노드와 소비자가 지원하는 것을 확인한 뒤 새 기능을 활성화합니다. 구버전 SDK는 참여 비율·기간·레이어·홀드아웃을 해석하지 못하므로 혼합 버전에서 해당 기능을 사용하지 않습니다. 고급 분석 메타데이터도 전체 수신 노드 업그레이드 후 전송합니다.

## 인증

`PRISM_API_KEYS`는 32–512자의 무작위 키이며 쉼표로 여러 키를 지정해 교체할 수 있습니다. 모든 `/v1/*` 요청에 `X-Prism-Api-Key` 헤더가 필요합니다. SDK 키는 서버 측 코드에서 사용하고, 키 변경 후 API를 재시작합니다.

Admin은 `PRISM_ADMIN_USERNAME`과 BCrypt `PRISM_ADMIN_PASSWORD_HASH`가 필요합니다. 조회 전용 계정은 `PRISM_ADMIN_VIEWER_USERNAME`과 `PRISM_ADMIN_VIEWER_PASSWORD_HASH`를 함께 지정하며 관리자와 다른 이름을 사용합니다. 계정 변경 후 Admin을 재시작합니다. 외부 운영에는 HTTPS와 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`를 설정합니다.

## SDK 운영 계약

기본 SDK·코어는 Spring 실행 의존성을 가져오지 않습니다. Spring 스타터와 API/Admin에는 기존 규칙을 유지하는 SpEL 호환 모듈이 포함됩니다. 일반 SDK에서 SpEL을 사용한다면 `prism-targeting-spel`을 추가하세요. 데이터 규칙 형식·평가기 주입·거절 진단·업그레이드는 [프레임워크 독립 SDK 안내](docs/framework-independent-sdk.md)를 참고하세요.

기본 모드는 LOCAL입니다. `evaluate()`로 화면을 준비하고 실제 경험 제공 시 `recordExposure()`를 호출합니다. `assign()`은 평가와 실제 노출 등록을 함께 수행합니다. 전환은 `track(outcome, eventName)`으로 원래 노출 참조를 전달하며, 전환 시점에 새 노출을 만들지 않습니다. LOCAL 전환의 `true`는 메모리 큐 등록, REMOTE의 `true`는 서버 수락을 뜻합니다. 전송 결과는 `flush()`와 거부 로그로 확인합니다.

Spring 전략 Resolver는 양수 비중의 모든 변형에 구현이 있는지 검사한 뒤 노출을 등록합니다. 전략이 누락된 폴백은 해당 실험의 노출·전환에서 제외합니다. `@PrismTrackConversion`의 정상 반환 이벤트는 동기 Spring 트랜잭션의 커밋 성공 후 추적하며, 롤백/커밋 실패에는 전송하지 않습니다. 직접 SDK 호출은 커밋 후 수행하세요. 배정·전환의 사용자 ID와 실험 키는 공백만으로 구성할 수 없고 255자 이하여야 하며, 대소문자·유니코드·앞뒤 공백을 변경하지 않습니다. REMOTE 전략 사용자는 새 API를 먼저 배포하세요. 자세한 계약은 [스타터 README](prism-spring-boot-starter/README.md)를 참고하세요.

LOCAL 노출은 공유 client 수명 동안 중복 제거하며 기본 한도는 사용자×실험 100,000개입니다. TTL이나 자동 퇴출이 없고 새 조합이 한도를 넘으면 `9998`로 거절합니다. 누적 사용자 수·거절 카운터와 실제 힙 사용량을 기준으로 한도를 정합니다. 프로세스 경계를 넘는 중복은 DB의 고유 사용자 집계로 처리합니다. 이벤트 재전송에는 원래 ID와 payload를 유지하며, 별도 전환 호출은 서로 다른 행동으로 저장합니다. 메모리 전송 큐는 강제 종료 시 유실될 수 있습니다.

목표·변형·타기팅 등 배정 설정은 시작 이후 잠깁니다. 설명 수정과 일시중지·종료는 가능하지만 DRAFT 복귀나 종료 실험 재시작은 허용하지 않습니다. 설정을 바꾸는 실험은 새 키로 생성합니다. 가드레일 이벤트는 목표 CVR과 별도로 집계하며 자동 중단이나 별도 유의성 판정은 제공하지 않습니다. SDK 호출·옵션·전환 귀속의 상세 예시는 [SDK README](prism-sdk/README.md)를 참고하세요.

## 레이어와 홀드아웃

`/admin/population`에서 레이어를 만들고 실험별로 `[start, end)` 범위를 지정합니다. 전체 범위는 `[0, 10000)`이며 같은 레이어의 범위는 겹칠 수 없습니다. 종료 실험도 범위를 예약하므로 재사용하려면 새 레이어 키를 만듭니다.

전역 홀드아웃은 키와 비율을 한 번만 설정합니다. 단위는 basis point이며 `500`은 5%입니다. `0`으로 확정해도 이후 변경할 수 없습니다. 홀드아웃 사용자는 모든 실험 배정에서 제외됩니다. 누적 결과를 비교하려면 LOCAL의 `recordPopulationExposure(userId)`를 공통 서비스 진입 시 모든 사용자에게 호출하고, 전환 시 `trackPopulationConversion(userId, eventName)`을 호출합니다. 같은 client의 선행 모집단 노출이 필요하며 실험 지표와 별도로 집계합니다.

`stickyBucketing`은 최초 변형을 유지하면서 상태·기간·타기팅 등 참여 조건을 계속 검사합니다. REMOTE는 DB에, LOCAL 기본은 메모리에 저장합니다. LOCAL 재시작 간 유지는 `FileStickyAssignmentStore` 또는 스타터의 `sticky-assignments-directory`로 구성합니다. 영속 디렉터리는 환경별로 분리하며 여러 호스트 간 공유가 필요하면 사용자 정의 저장소를 사용합니다.

## 이벤트 수집과 설정 전파

기본 `DIRECT` 모드는 배치 이벤트를 동기 저장합니다. Kafka 수집은 API에 다음과 같이 설정합니다.

```yaml
prism:
  pipeline:
    mode: KAFKA
    bootstrap-servers: localhost:9092
    topic: prism.events.v1
    warehouse-topic: prism.warehouse.v1
    dead-letter-topic: prism.dead-letter.v1
    group-id: prism-materializer-v1
    consumer-enabled: true
    max-outbox-rows: 100000
```

토픽과 consumer group은 DB·환경별로 분리하고 토픽의 복제·보존 정책을 구성합니다. Kafka 보안 속성은 `prism.pipeline.client-properties`로 전달합니다. 로컬 브로커는 `docker compose -f docker/docker-compose.kafka.yml up -d`로 실행합니다. `consumer-enabled: true`인 worker가 적어도 하나 필요합니다.

`QUEUED`는 Kafka 수신 확인이며 DB 반영은 비동기입니다. 수신 실패는 `RETRY`이고 DB 저장으로 자동 전환하지 않습니다. 기존 REMOTE 할당·전환은 동기 DB 경로를 유지합니다. 노출보다 먼저 도착한 전환은 최대 7일간 재시도하며 영구 거부는 DLQ로 확인합니다. warehouse 출력은 귀속 검증된 이벤트이며 외부 consumer가 `eventId`로 upsert·중복 제거해야 합니다. 외부 웨어하우스 연결은 별도로 구성합니다.

inbox 대기량, outbox 크기와 consumer lag를 관측합니다. DONE/REJECTED inbox와 영수증은 자동 삭제하지 않습니다. 이미 거부된 이벤트는 같은 payload 재전송만으로 재처리되지 않습니다. 재처리하려면 거부 원인을 해결한 뒤 DLQ의 payload hash와 일치하는 inbox 행을 확인하고 상태를 PENDING, `retry_at`을 현재 UTC 시각으로 변경합니다. payload를 수정하는 경우 새 이벤트 ID와 일치하는 전환 참조를 사용합니다.

SSE는 SDK의 `configStreaming = true` 또는 스타터의 `prism.client.config-streaming: true`로 활성화합니다. `/v1/config/stream`도 API 키를 사용합니다. 기존 폴링과 마지막 유효 설정을 유지하며 장애 시 재연결합니다. 프록시의 응답 버퍼링을 끄고 SSE 연결·인증 헤더를 통과시킵니다. 전파 지연이 있으므로 즉시 일관성을 보장하지 않습니다.

## 고급 분석

실험 상세의 **고급 분석**에서 노출 전 DRAFT 실험의 계획을 고정합니다. 대조군, 목표 전환 관측 기간(1–720시간), 수집 지연 허용 기간(0–168시간)을 등록하며 양수 가중치 변형 2–8개를 지원합니다. 계획 저장 즉시 실험 정의도 잠깁니다. 이미 시작·예약·노출된 실험에는 소급 적용하지 않습니다.

사용자별 첫 실제 노출부터 `[노출 시각, 노출 시각 + 관측 기간)`의 목표 전환 여부를 사용하고 지연 허용 시간 후 결과를 확정합니다. 확정 후 결과를 바꿀 늦은 이벤트, 복수 변형 노출 등은 무효 사유로 기록하고 추론을 차단합니다. SRM 통과·노출 수와 분석 등록 수 일치·무효 사용자 0명이 추론 조건입니다.

사전 세그먼트와 CUPED 지표는 LOCAL 최초 노출에 명시적으로 전달합니다. 어노테이션이나 타기팅 속성에서 자동 추출하지 않습니다.

```kotlin
import io.github.silbaram.prism.common.rest.dto.event.ExposureAnalysisContext

val context = ExposureAnalysisContext(
    segments = mapOf("device" to "mobile"),
    baselineValue = 7.0,
    baselineMeasuredAt = "2026-09-01T00:00:00Z"
)
val assignment = experiments.assign(userId, "checkout", attributes = emptyMap(), analysis = context)
experiments.track(assignment, "purchase")
```

baseline은 계획의 사전 기간에 계산한 유한 값이며 절댓값 10억 이하여야 합니다. 값과 시각을 함께 전달하고 시각은 첫 노출 이전 및 계획의 UTC 마감 이하여야 합니다. 0은 실제 관측값이고 누락과 다릅니다. 세그먼트는 노출 전에 정해진 속성만 사용합니다. 첫 노출 후 재호출로 메타데이터를 보충할 수 없으며 REMOTE 편의 API는 context를 지원하지 않습니다.

- 순차 검정: 모든 변형·표본 수를 포함하는 보수적인 95% confidence sequence입니다.
- Bayesian: `Beta(1,1)` 사전분포와 고정 시드 추출로 대조군 대비 확률·사후 구간·기대 손실을 표시합니다. p-value와 다른 의미입니다.
- CUPED: 비교할 모든 사용자에게 유효 baseline이 있고 각 군 30명 이상이며 사전 지표 분산이 있을 때 계산합니다. 표준오차는 진단용 근사치이며 별도 유의성 판정은 없습니다.
- 세그먼트: 사전 등록 집단의 기술 통계이며 사후 탐색 검정이나 인과 효과 판정을 제공하지 않습니다.

적어도 하나의 Admin에서 `prism.analysis.finalization-enabled=true`가 필요합니다. 확정 작업은 기본 30초 간격이며 `prism.analysis.finalization-interval-ms`로 조정합니다. 미확정·무효 사용자 수와 처리 backlog를 확인합니다. 일반 CVR과 고급 분석은 분모가 다를 수 있습니다.

## Spring 개발자 적용 흐름

1. Admin의 **이벤트 카탈로그**(`/admin/events`)에서 `cart`, `checkout`, `purchase` 등의 이름과 설명을 등록합니다. 등록 전이라도 기존 목표·가드레일과 유효하게 수집된 이벤트는 자동으로 검색됩니다. 카탈로그 등록은 이벤트 생성이나 실험 설정 변경을 의미하지 않습니다.
2. 실험 생성·수정 화면에서 검색해 목표와 가드레일을 선택합니다. 직접 입력도 가능합니다. 이벤트 이름의 대소문자와 앞뒤 공백을 그대로 보존하므로 SDK와 동일한 이름을 사용하세요. 시작한 실험의 설정 잠금은 그대로 유지됩니다.
3. 실험 상세의 **SDK 적용 가이드**에서 의존성·연결 설정과 해당 실험/이벤트의 Java·Kotlin 코드를 복사합니다. JDK 21·Spring Boot 4 기준이며 SDK 아티팩트는 먼저 로컬 또는 사내 Maven 저장소에 게시해야 합니다. 현재 버전이 공개 저장소에 게시되어 있다고 가정하지 않습니다. API 키는 복사 코드에 포함하지 않고 소비 애플리케이션의 `PRISM_CLIENT_API_KEY` 환경 변수로 설정합니다.
4. 실제 경험을 제공할 때 노출을 기록하고, 행동 성공·트랜잭션 커밋 후 같은 사용자 ID로 이벤트를 추적합니다. 비동기·다중 프로세스 귀속은 원래 `AssignmentOutcome` 전달 방식을 사용하세요. 자세한 API 사용법은 [SDK README](prism-sdk/README.md)를 참고하세요.
5. **이벤트별 집계**에서 정확한 이벤트 이름으로 수집 여부와 변형별 CVR을 확인하고 **순서형 퍼널**에서 검색한 이벤트를 차례로 추가합니다. LOCAL 추적의 `true`는 메모리 큐 등록 성공이며 DB 반영 확인은 아닙니다.
6. **사용자 여정 보기**에서 SDK에 전달한 사용자 ID를 검색해 수집된 행동을 확인합니다. `purchase`만 기록했다면 구매 전 과정은 나오지 않습니다. 필요한 중간 행동과 실패·재시도를 같은 사용자 ID로 기록하세요. 실패도 별도 이벤트 이름으로 기록하며 실제 재시도 행동을 중복 전송과 구분합니다.
7. **경로 패턴 보기**에서 변형별로 자주 나타나는 행동 경로와 목표 발생률을 비교합니다. 사용자 수를 눌러 전체 목록을 조회하고, 목표 발생 여부로 좁힌 뒤 개별 타임라인을 확인합니다.

카탈로그는 같은 Prism 서버의 전체 실험을 대상으로 하며, 검색 결과는 최대 100개입니다. 입력한 이름과 정확히 일치하는 이벤트는 우선 표시하며, 등록 후에는 해당 이름의 검색 결과로 이동합니다. 이름과 설명은 Admin만 등록할 수 있고 Viewer도 조회·검색·가이드를 사용할 수 있습니다. 카탈로그에 아직 등록하지 않은 이벤트도 정상 수집·집계합니다. 발생 횟수와 수집된 실험 수는 유효한 노출 귀속이 있는 로그만 사용하며 최근 발생은 서버 수신 시각이 아닌 저장된 이벤트 시각(UTC)입니다.

이벤트별 CVR은 **해당 이벤트 발생 고유 사용자 / 같은 변형의 노출 고유 사용자**로 전체 기간을 집계합니다. 노출이 있지만 이벤트가 없으면 0%, 노출이 없으면 미측정(—)입니다. 반복 이벤트는 발생 횟수에만 추가되며 사용자 수를 늘리지 않습니다. 목표·가드레일은 수집 전에도 표시하고 다른 이름은 검색으로 확인할 수 있습니다. 조회 이벤트를 바꿔도 목표 CVR은 바뀌지 않습니다. 이벤트별 사용자 수를 합산하거나 이 화면을 승자·유의성 판정으로 사용하지 마세요.

이 단계는 Spring 개발자와 내부 운영자의 적용 흐름을 개선합니다. 전송 실패 원인 진단 화면, 프로젝트·환경 분리와 다른 언어 SDK는 후속 범위입니다. 이벤트 카탈로그와 전역 집계 조회의 대규모 데이터 성능은 별도 검증해야 합니다.

## 순서형 퍼널

실험 상세나 이벤트별 집계의 **순서형 퍼널 보기**에서 이벤트를 검색해 순서대로 추가하거나 한 줄씩 2–8개 입력합니다. 조회 기간은 최대 366일, 첫 단계 이후 제한 시간은 1–720시간이며 기본값은 최근 30일·24시간입니다. Admin과 Viewer 모두 조회할 수 있습니다.

- 조회 구간은 `[시작, 종료)`이며 UTC·마이크로초 정밀도를 사용합니다. 시작은 1970년 이후, 종료는 현재 시각 이하여야 합니다.
- 기간 안의 최초 1단계부터 사용자×변형당 한 번 분석합니다. 이벤트 이름의 대소문자·공백을 구분하고 같은 이름을 여러 단계에 지정하지 않습니다.
- 다음 단계는 앞 단계보다 엄격히 늦고 첫 단계부터 제한 시간 미만이어야 합니다. 단계 건너뛰기·같은 시각 진행은 인정하지 않으며 첫 단계 반복으로 시간을 재설정하지 않습니다. 선택하지 않은 이벤트가 중간에 있어도 경로는 유지됩니다.
- 제한 시간이 조회 종료까지 끝난 사용자만 집계합니다. 아직 끝나지 않았다면 마지막 단계를 수행했어도 **관측 중**으로 분리합니다.
- 이전 단계 대비는 `현재 단계 사용자 / 이전 단계 사용자`, 첫 단계 대비는 `현재 단계 사용자 / 첫 단계 사용자`입니다. 분모가 0이면 `—`로 표시합니다.
- 유효한 노출 귀속이 있는 이벤트만 사용합니다. 변형별 기술 통계이므로 복수 변형에 포함된 사용자를 합쳐 전체 고유 사용자로 해석하지 않습니다. 승자·유의성·인과 효과를 판정하지 않습니다.

단계별 사용자 수를 누르면 해당 단계 **도달 사용자**를, **다음 단계 미도달** 수를 누르면 현재 단계까지만 도달한 사용자를 조회합니다. 관측 중 숫자는 별도 목록으로 연결됩니다. 목록과 집계가 동일한 판정 로직을 공유하며, 사용자 ID를 누르면 같은 실험·변형·기간·단계·제한 시간을 유지한 타임라인으로 이동합니다.

저장된 발생 시각을 기준으로 하며 늦게 수집된 이벤트나 SDK 호스트 시계 오차로 결과가 달라질 수 있습니다. 이벤트는 1,000행씩 조회하고 사용자 목록은 한 페이지와 다음 페이지 유무만 보관합니다. DB 스캔·정렬 비용은 데이터에 비례할 수 있으므로 대규모 데이터 성능은 별도 검증해야 합니다.

### 저장된 퍼널

실험 상세 또는 순서형 퍼널의 **저장된 퍼널**(`/admin/experiments/{id}/funnels`)에서 자주 쓰는 분석 조건을 관리합니다. Admin은 생성·수정·복제·삭제할 수 있고 Viewer는 목록과 분석 결과를 조회할 수 있습니다. 기존 DB는 배포 전에 `037_saved_funnels.sql`을 적용하며 신규 DB의 `schema.sql`에는 포함되어 있습니다.

- 이름·설명, 이벤트 순서 2–8개, 첫 단계 이후 제한 시간 1–720시간과 조회 기간을 저장합니다. 이름은 앞뒤 공백을 제거한 1–120자이며 같은 실험 안에서 대소문자를 구분해 중복을 금지합니다. 이벤트 이름의 대소문자와 앞뒤 공백은 그대로 보존합니다.
- **최근 7일·30일**은 조회 시점의 UTC 시각부터 정확히 7일·30일 전까지의 구간입니다. 다시 열면 기간을 새로 계산하며, 한 분석에서 사용자 목록·타임라인으로 이동할 때는 처음 계산한 실제 시작·종료 시각을 유지합니다. 달력상의 날짜나 세션 기준이 아닙니다.
- **고정 기간**은 UTC `[시작, 종료)`를 마이크로초 정밀도로 저장합니다. 최대 366일이며 종료는 현재 시각 이하여야 합니다. 동일 조건을 다시 조회해도 늦게 도착한 데이터에 따라 결과는 달라질 수 있습니다.
- 기존 퍼널에서 **현재 조건 저장**을 누르면 지금 입력한 단계·기간·제한 시간으로 저장 화면을 엽니다. 저장된 퍼널의 결과 화면에서 조건을 바꿔 조회하면 임시 분석이 되며 원본은 바뀌지 않습니다. 저장된 조건의 변경은 **저장된 조건 수정**에서 수행합니다.
- **복제**는 원본을 바꾸지 않고 새 저장 화면을 엽니다. 새 이름을 확인한 뒤 저장해야 생성됩니다. **삭제**는 분석 조건만 지우며 이벤트 기록과 실험 설정은 유지합니다. 사용하지 않은 실험 자체를 삭제하면 그 실험의 저장된 퍼널도 삭제됩니다.
- 동시에 같은 퍼널을 수정하거나 오래 열린 화면에서 삭제하면 기준 버전을 확인하여 충돌로 거절합니다. 수정 충돌·입력 오류 화면은 입력 내용을 유지하고 최신 저장 조건을 다시 열 수 있게 합니다. 목록은 50개씩 이어서 조회합니다.

분석 결과와 판정 기준은 기존 순서형 퍼널을 그대로 사용합니다. 저장된 퍼널은 실험의 트래픽 배분·목표 이벤트·확정 분석 계획을 변경하지 않습니다.

최대 길이의 한글 이벤트 이름 8개를 포함한 상세 조회 URL도 처리하도록 Admin의 요청 줄·헤더 한도는 64KB입니다. 앞단 리버스 프록시도 해당 길이의 요청 줄과 헤더를 허용하도록 설정해야 합니다. API 서버의 한도는 별도입니다.

## 사용자 여정

실험 상세 → **사용자 여정 보기**(`/admin/experiments/{id}/journeys`)에서 기간·사용자 ID·변형·목표 이벤트 발생 여부로 조회합니다. 기본 최근 30일, 최대 366일, UTC `[시작, 종료)` 구간이며 한 페이지는 기본 50개·최대 100개입니다. Admin과 Viewer 모두 읽을 수 있습니다.

상세 화면에서 목록으로 돌아오면 기존 검색 조건과 페이지 위치가 유지됩니다. 기본 최근 30일로 시작한 경우에도 처음 조회한 시작·종료 시각을 유지합니다. 한 상세 요청의 타임라인·요약·퍼널 판정은 같은 DB 스냅샷으로 조회합니다. 조회 시각에 마이크로초가 있으면 날짜 선택기 대신 UTC 문자열 입력으로 표시하여 기간 경계의 정밀도를 보존합니다.

- 목록은 기간 내 노출 또는 이벤트가 있는 **사용자×변형** 단위입니다. 사용자 ID와 변형은 정확히 일치하며 대소문자·앞뒤 공백을 보존합니다. 첫/마지막 시각과 횟수는 조회 기간 내 관측값입니다. 노출만 있고 이벤트가 없는 사용자도 표시합니다.
- 목표 발생 여부는 기존 CVR과 같은 유효한 노출 귀속 기준을 사용합니다. 기간 밖의 노출에 연결된 이벤트도 유효할 수 있습니다. 이벤트 수에는 집계 제외 진단 로그가 포함되며 제외 횟수를 별도로 표시합니다. 목표 미설정 실험은 발생 여부 필터를 사용할 수 없습니다.
- 타임라인은 노출·행동을 발생 시각순으로 병합합니다. 같은 시각은 노출/행동 구분과 행 ID로 표시 순서를 고정하지만 실제 선후 관계를 주장하지 않습니다. 실제 반복 호출은 별도 행동으로 보존합니다. 기존 이벤트 ID 중복 제거 계약은 바뀌지 않습니다.
- 명시적 노출 연결, 과거 로그의 선행 시각 기준 귀속, 귀속 확인 불가를 구분합니다. 명시적으로 연결된 노출보다 시각이 이른 이벤트는 시계 확인 대상으로 표시합니다. 기간 밖 노출과 별도 아카이브에 옮긴 과거 로그는 타임라인에 포함하지 않습니다.
- 목록은 마지막 사용자·변형, 타임라인은 마지막 시각·로그 종류·ID 기준으로 이어 읽습니다. 타임라인 경과 시간은 페이지 경계에서도 이어집니다. 페이지 사이에 지연 이벤트가 들어올 수 있으므로 결과는 고정된 스냅샷이 아니며 처음부터 다시 조회할 수 있습니다.
- 퍼널의 **미도달**은 직전 단계까지 도달했으나 선택 조건 내 다음 단계에 도달하지 않은 상태입니다. 영구 이탈을 뜻하지 않습니다. 관측 중은 완료 행동이 있어도 별도로 표시하며, 단순 목표 발생 여부와 퍼널 판정은 다를 수 있습니다.

기존 SDK와 API 요청 형식은 바뀌지 않습니다. 여정 조회 인덱스는 신규 DB의 `schema.sql` 또는 기존 DB의 `036_user_journeys.sql`로 추가합니다. 변형 인덱스만 191자 접두부를 사용해 InnoDB 키 크기 제한을 지키며, 실제 조회는 전체 식별자를 비교합니다. 세션 구분·자동 행동 수집·전체 서비스의 사용자 프로필은 제공하지 않습니다.

### 경로 패턴

실험 상세 또는 사용자 여정 목록의 **경로 패턴 보기**(`/admin/experiments/{id}/journeys/patterns`)에서 기간·변형·최대 이벤트 수(2–8개, 기본 5개)·변형별 상위 경로 수(1–50개, 기본 20개)를 지정합니다. 기간은 여정 조회와 동일한 UTC `[시작, 종료)`이며 기본 30일·최대 366일입니다. Admin과 Viewer 모두 조회할 수 있습니다. 기존 SDK로 기록한 이벤트를 사용하므로 별도의 추적 코드나 DB 마이그레이션은 필요하지 않습니다.

- 기간 안의 유효하게 노출에 귀속된 이벤트로 **사용자×변형당 한 경로**를 만듭니다. 노출 자체와 귀속 불명 로그는 경로에 넣지 않습니다. 반복 행동은 보존하고 이벤트 이름의 대소문자·공백을 구분합니다.
- 같은 시각의 행동은 순서 없는 묶음입니다. 묶음 내부의 이름을 정렬해 수집 순서가 달라도 같은 패턴으로 집계하며 반복 횟수는 유지합니다. 길이 한도가 묶음 중간에 걸리면 묶음 직전까지만 표시합니다. 첫 묶음부터 한도를 넘는 사용자는 제외 수로 표시합니다.
- **이후 행동 있음** 경로와 **기간 내 마지막 행동** 경로를 구분합니다. 경로의 끝은 영구 이탈이 아닙니다. 세션 경계나 기간 밖의 과거 행동은 추정하지 않습니다.
- 비중은 `해당 경로 사용자 / 해당 변형에서 유효한 이벤트가 있는 모든 사용자`입니다. 표시하지 않은 경로와 길이 초과 제외 사용자도 분모에 포함하며 각각의 수를 표시합니다. 변형 간 사용자 수를 합산해 전체 고유 사용자로 해석하지 않습니다.
- 목표 발생률은 `기간 내 목표 이벤트가 있는 경로 사용자 / 해당 경로 사용자`입니다. 표시 길이 이후의 목표도 포함합니다. 순서·관측 시간을 적용한 퍼널 전환율이나 인과 효과·유의성 판정이 아닙니다. 목표 미설정 실험은 미측정으로 표시합니다.
- 경로의 **사용자 수**를 누르면 해당 변형·경로의 전체 사용자 목록으로 이동합니다. **목표 발생 사용자 수**는 목표 발생 필터를 적용한 목록으로 연결합니다. 전체·발생 확인·발생 확인 안 됨으로 필터링하고 1–100명씩 조회할 수 있습니다. 목표 미설정 실험에서는 전체 목록만 제공합니다.
- 목록은 집계와 같은 경로 판정을 사용하며 전체 경로 인원·목표 발생 인원·현재 필터에 맞는 인원을 표시합니다. 첫·마지막 시각과 이벤트 수는 기간 내 유효한 이벤트 전체를 기준으로 합니다. 타임라인에는 노출·집계 제외 로그도 포함되어 행 수가 다를 수 있습니다.
- 사용자 타임라인에서 페이지를 이동한 뒤에도 목록의 기간·변형·목표 필터·페이지 위치·경로 길이·표시 수를 복원합니다. 경로 집계로 돌아가면 원래의 전체 변형/특정 변형 선택도 복원합니다. 기존 최대 3명의 예시 사용자 연결은 유지하며, 이는 ID 순서의 예시이지 무작위 표본이 아닙니다.
- 목록의 인원과 페이지는 한 요청 안에서 같은 DB 스냅샷으로 계산합니다. 요청 사이에 늦게 수집된 이벤트가 반영되면 경로 분류와 인원이 바뀔 수 있습니다. 경로를 더 이상 찾을 수 없으면 집계 화면에서 다시 선택하도록 안내합니다.

집계 요청은 같은 DB 스냅샷에서 이벤트를 1,000행씩 읽습니다. 현재 사용자는 최대 8개 이벤트 이름, 각 경로는 최대 3개 예시 ID만 보관합니다. 한 요청의 이벤트 200,000건 또는 서로 다른 경로 5,000개를 넘으면 부분 결과 대신 기간·변형을 좁히라는 오류를 표시합니다. 이 한도는 DB 스캔·정렬 시간의 상한을 보장하지 않으므로 대규모 운영 데이터 성능은 별도 검증해야 합니다.

경로 사용자 목록도 선택한 변형의 전체 이력을 읽어 동일한 200,000건 제한을 적용합니다. 전체 사용자 목록을 메모리에 쌓지 않고 한 페이지와 다음 페이지 확인용 1명만 보관하며, 선택한 경로 하나만 집계하므로 5,000개 경로 제한은 적용하지 않습니다. 페이지를 넘길 때도 전체 이력을 다시 읽어 인원을 계산하므로 대량 데이터에서는 기간을 좁혀 사용하세요.

## 검증

```bash
./gradlew build
./gradlew :prism-admin:test --tests io.github.silbaram.prism.admin.AdminMetricsIntegrationTest
./gradlew :prism-admin:test --tests io.github.silbaram.prism.admin.UserJourneyIntegrationTest
# 테스트 전용 MySQL 서버의 URL·계정을 환경 변수로 지정한 뒤 실행
./gradlew :prism-api:mysqlTest
# 테스트 전용 Kafka 브로커를 지정한 뒤 실행
./gradlew :prism-api:kafkaTest
```

MySQL 검증은 `PRISM_TEST_MYSQL_URL`, `PRISM_TEST_MYSQL_USERNAME`, `PRISM_TEST_MYSQL_PASSWORD`를 사용합니다. 임의 이름의 DB를 생성·삭제하므로 테스트 서버에 CREATE/DROP DATABASE 권한이 필요합니다. Kafka 검증은 `PRISM_TEST_KAFKA_BOOTSTRAP`을 사용합니다. 두 통합 검증은 일반 `test`에 포함되지 않습니다. 전체 `build`에는 POM·Gradle module metadata 각각으로 독립 Java 소비자를 실행하는 SDK 게시 검증이 포함됩니다.

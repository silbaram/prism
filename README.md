# Prism (A/B Testing System)

Prism은 무상태 트래픽 분배 엔진, 실험 관리 Admin, 설정·이벤트 API로 구성된 A/B 테스트 플랫폼입니다. SDK는 동기화한 설정으로 로컬 SpEL 타기팅과 MurmurHash 분배를 실행하고, 노출·전환을 배치 전송합니다.

## 주요 특징
- **무상태 트래픽 분배**: 고성능 API로 사용자별 변형(variant) 할당
- **실험 관리/Admin**: 목표 이벤트 설정·사용자 단위 CVR·95% 신뢰구간 제공
- **Fail-safe SDK**: 설정 동기화 장애 시 마지막 정상 설정으로 평가하며, 설정이 없으면 기본 동작으로 폴백
- **Spring 통합**: `@PrismExperiment` 어노테이션과 안전한 전환 추적 래퍼 제공

## 프로젝트 구조
- **prism-core**: MurmurHash 기반 트래픽 분배 및 SpEL 타기팅 유틸리티
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
- [고급 분석](#고급-분석), [순서형 퍼널](#순서형-퍼널)

## 전환 지표

실험별 목표 이벤트를 설정하면 **목표 이벤트 발생 고유 사용자 / 노출 고유 사용자**로 CVR을 계산합니다.
반복 이벤트는 사용자별 한 번만 집계하며, 보조/실패 이벤트는 별도 화면에서 확인합니다.
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

DDL은 암묵적으로 커밋됩니다. 롤백은 쓰기를 중단한 상태에서 백업 복원과 이전 애플리케이션 배포를 함께 수행합니다. 027의 `log_conversion_unattributed_archive`와 기존 영수증은 자동 삭제하지 않습니다. 과거 전환의 노출 참조나 실험 목표를 임의로 채우지 않으며, 로그 정리 시 전환의 노출 외래키와 중복 제거 보존 기간을 함께 고려합니다.

로그의 `LocalDateTime`은 UTC 값입니다. 사용자 지정 JDBC URL에도 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true`를 반영합니다. 식별자는 대소문자·악센트·후행 공백을 구분하고 저장 시각은 마이크로초 정밀도를 사용합니다.

DB 변경 후 API/Admin과 Kafka worker를 함께 갱신합니다. SDK/스타터 소비자를 다시 컴파일하고, 모든 수신 노드와 소비자가 지원하는 것을 확인한 뒤 새 기능을 활성화합니다. 구버전 SDK는 참여 비율·기간·레이어·홀드아웃을 해석하지 못하므로 혼합 버전에서 해당 기능을 사용하지 않습니다. 고급 분석 메타데이터도 전체 수신 노드 업그레이드 후 전송합니다.

## 인증

`PRISM_API_KEYS`는 32–512자의 무작위 키이며 쉼표로 여러 키를 지정해 교체할 수 있습니다. 모든 `/v1/*` 요청에 `X-Prism-Api-Key` 헤더가 필요합니다. SDK 키는 서버 측 코드에서 사용하고, 키 변경 후 API를 재시작합니다.

Admin은 `PRISM_ADMIN_USERNAME`과 BCrypt `PRISM_ADMIN_PASSWORD_HASH`가 필요합니다. 조회 전용 계정은 `PRISM_ADMIN_VIEWER_USERNAME`과 `PRISM_ADMIN_VIEWER_PASSWORD_HASH`를 함께 지정하며 관리자와 다른 이름을 사용합니다. 계정 변경 후 Admin을 재시작합니다. 외부 운영에는 HTTPS와 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`를 설정합니다.

## SDK 운영 계약

기본 모드는 LOCAL입니다. `evaluate()`로 화면을 준비하고 실제 경험 제공 시 `recordExposure()`를 호출합니다. `assign()`은 평가와 실제 노출 등록을 함께 수행합니다. 전환은 `track(outcome, eventName)`으로 원래 노출 참조를 전달하며, 전환 시점에 새 노출을 만들지 않습니다. LOCAL 전환의 `true`는 메모리 큐 등록, REMOTE의 `true`는 서버 수락을 뜻합니다. 전송 결과는 `flush()`와 거부 로그로 확인합니다.

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

## 순서형 퍼널

실험 상세나 이벤트별 집계의 **순서형 퍼널 보기**에서 이벤트를 한 줄씩 2–8개 입력합니다. 조회 기간은 최대 366일, 첫 단계 이후 제한 시간은 1–720시간이며 기본값은 최근 30일·24시간입니다. Admin과 Viewer 모두 조회할 수 있습니다.

- 조회 구간은 `[시작, 종료)`이며 UTC·마이크로초 정밀도를 사용합니다. 시작은 1970년 이후, 종료는 현재 시각 이하여야 합니다.
- 기간 안의 최초 1단계부터 사용자×변형당 한 번 분석합니다. 이벤트 이름의 대소문자·공백을 구분하고 같은 이름을 여러 단계에 지정하지 않습니다.
- 다음 단계는 앞 단계보다 엄격히 늦고 첫 단계부터 제한 시간 미만이어야 합니다. 단계 건너뛰기·같은 시각 진행은 인정하지 않으며 첫 단계 반복으로 시간을 재설정하지 않습니다. 선택하지 않은 이벤트가 중간에 있어도 경로는 유지됩니다.
- 제한 시간이 조회 종료까지 끝난 사용자만 집계합니다. 아직 끝나지 않았다면 마지막 단계를 수행했어도 **관측 중**으로 분리합니다.
- 이전 단계 대비는 `현재 단계 사용자 / 이전 단계 사용자`, 첫 단계 대비는 `현재 단계 사용자 / 첫 단계 사용자`입니다. 분모가 0이면 `—`로 표시합니다.
- 유효한 노출 귀속이 있는 이벤트만 사용합니다. 변형별 기술 통계이므로 복수 변형에 포함된 사용자를 합쳐 전체 고유 사용자로 해석하지 않습니다. 승자·유의성·인과 효과를 판정하지 않습니다.

저장된 발생 시각을 기준으로 하며 늦게 수집된 이벤트나 SDK 호스트 시계 오차로 결과가 달라질 수 있습니다. 이벤트는 1,000행씩 조회하지만 대규모 DB 정렬 비용은 별도 관측해야 합니다. 이 기능은 순서형 퍼널이며 개별 사용자 여정·세션 타임라인은 제공하지 않습니다. 별도 스키마 변경은 필요하지 않습니다.

## 검증

```bash
./gradlew build
./gradlew :prism-admin:test --tests io.github.silbaram.prism.admin.AdminMetricsIntegrationTest
# 테스트 전용 MySQL 서버의 URL·계정을 환경 변수로 지정한 뒤 실행
./gradlew :prism-api:mysqlTest
# 테스트 전용 Kafka 브로커를 지정한 뒤 실행
./gradlew :prism-api:kafkaTest
```

MySQL 검증은 `PRISM_TEST_MYSQL_URL`, `PRISM_TEST_MYSQL_USERNAME`, `PRISM_TEST_MYSQL_PASSWORD`를 사용합니다. 임의 이름의 DB를 생성·삭제하므로 테스트 서버에 CREATE/DROP DATABASE 권한이 필요합니다. Kafka 검증은 `PRISM_TEST_KAFKA_BOOTSTRAP`을 사용합니다. 두 통합 검증은 일반 `test`에 포함되지 않습니다. 전체 `build`에는 POM·Gradle module metadata 각각으로 독립 Java 소비자를 실행하는 SDK 게시 검증이 포함됩니다.

# 이슈 #28 Phase 5 — 고급 실험 분석

실험 상세의 **고급 분석**에서 순차 검정, Bayesian 분석, CUPED, 세그먼트별 결과를 조회한다. 기존 상세 화면의 전체 기간 CVR/검정은 유지한다. 새 분석은 시작 전 계획을 만든 실험의 **고정된 사용자별 관측 기간 내 목표 전환 여부**를 대상으로 한다. 따라서 기존 화면과 분모가 다를 수 있다.

## 분석 계획과 결과 확정

1. DRAFT 실험의 목표 이벤트, 변형, 타기팅, 참여 기간 등을 먼저 설정한다.
2. 고급 분석에서 대조군, 전환 관측 기간(1–720시간), 수집 지연 허용 기간(0–168시간)을 등록한다. 양수 가중치 변형 2–8개를 지원한다.
3. 필요하면 사전 세그먼트(최대 5개, 각 허용 값 1–20개)와 CUPED 사전 지표를 지정한다. CUPED에는 모든 사용자에게 동일하게 적용할 지표의 이름·관측 구간과 UTC 마감 시각이 필요하다.
4. 계획을 고정한 뒤 실험을 시작한다. 계획 저장 즉시 실험 정의도 잠긴다. 노출 전에는 DRAFT를 유지하며 설명을 수정할 수 있다. 이후 시작·일시정지·참여 비율 확대는 기존 정책에 따라 가능하지만 계획/변형/목표는 바꿀 수 없다. 이미 시작·예약·노출된 실험에는 소급 적용하지 않는다.

사용자×실험의 첫 실제 노출 시각부터 `[노출 시각, 노출 시각 + 관측 기간)` 안의 유효한 목표 전환을 이진 결과로 사용한다. 반복 전환은 한 번으로 센다. 기간 밖의 전환은 기존 지표에는 남지만 새 분석 결과를 바꾸지 않는다. 기간 종료 후 지연 허용 시간까지 기다렸다가 Admin의 작업자가 결과를 한 번 확정한다. 조회 자체는 결과를 확정하지 않는다.

비교에는 DB에 저장된 마이크로초 정밀도의 시각을 사용한다. 입력 JSON의 나노초 값은 그대로 영수증/전송에 유지하지만, 재노출·늦은 전환 판정과 DB 전환 집계가 서로 다른 정밀도로 판단하지 않도록 한다.

확정 전 더 이른 노출이 도착하면 첫 노출과 분석 메타데이터를 함께 갱신한다. 동일/후속 노출은 최초 메타데이터를 덮어쓰지 않는다. 확정 후 늦게 도착한 전환으로 0→1 결과 변경이 필요하거나 더 이른 노출이 도착하면 무효 사유를 기록하고 고급 추론을 차단한다. 한 사용자의 복수 변형 노출과 계획 이전 노출도 차단 사유다. 결과를 조용히 수정하거나 무효 사용자를 제외해 분석하지 않는다.

SRM 통과, 노출 사용자 수와 분석 등록 수 일치, 무효 사용자 0명이 공통 추론 조건이다. 미확정 사용자와 무효 사용자 수는 화면에 표시한다. 기술 통계는 문제 확인을 위해 계속 표시한다. 무작위 배정, 사용자 간 독립성, 시간에 걸쳐 안정적인 변형별 전환율을 전제로 하며 장치별 중복 ID나 노출 선택 편향을 통계식으로 해결하지 않는다.

## SDK의 명시적 사전 정보 전달

```kotlin
import io.github.silbaram.prism.common.rest.dto.event.ExposureAnalysisContext

// baselineValue는 계획에 등록한 지표를 마감 이전 데이터로 계산한 값이다.
// baselineMeasuredAt는 그 계산에 포함된 데이터의 최종 관측 시각이다.
val context = ExposureAnalysisContext(
    segments = mapOf("device" to "mobile", "user_type" to "returning"),
    baselineValue = 7.0,
    baselineMeasuredAt = "2026-09-01T00:00:00Z"
)
val assignment = experiments.assign(userId, "checkout", attributes = emptyMap(), analysis = context)
experiments.track(assignment, "purchase")

// 준비와 실제 노출 시점이 다른 경우
val evaluation = client.evaluate(userId, "checkout")
val exposed = client.recordExposure(evaluation, context)
```

Java에서도 `new ExposureAnalysisContext(segments, baselineValue, baselineMeasuredAt)`와 4인자 `assign`을 사용한다. 스타터의 `PrismExperimentClient` 빈에 직접 전달할 수 있다. 어노테이션이나 타기팅 속성에서 자동 추출하지 않는다. 최초 노출 중복 제거가 메타데이터도 보존하므로 첫 노출부터 전달해야 한다. context 없이 먼저 노출한 뒤 재호출해서 보충할 수 없다.

`analysis`는 LOCAL 실험 노출에만 허용한다. 전환/모집단 이벤트에는 넣지 않는다. REMOTE 편의 API는 context를 지원하지 않으며 전달하면 할당 실패로 반환한다. REMOTE 노출도 계획의 순차/Bayesian 분석에는 참여하지만 사전 정보는 미수집 상태다.

세그먼트는 노출 이전에 정해진 속성만 사용한다. 미등록/누락 값은 `(미수집·미등록)`으로 합쳐 표시한다. 노출 후 행동으로 세그먼트를 결정하지 않는다. 기본 targeting attributes는 자동 전송되지 않는다.

baseline은 유한한 수이며 절댓값 10억 이하여야 한다. 값과 시각은 함께 제공한다. 0은 실제 관측값이며 누락과 다르다. 시각이 계획의 마감보다 늦거나 첫 노출 이전이 아니면 유효 baseline으로 인정하지 않는다. 사전 기간과 산식의 일관성은 호출 애플리케이션이 보장해야 한다. 값을 임의로 0으로 대체하거나 처치 이후 데이터를 사용하면 안 된다.

HTTP 이벤트에는 선택적 `analysis: {segments, baselineValue, baselineMeasuredAt}`가 추가된다. DIRECT 저장, Kafka inbox/materialization, warehouse 출력에 동일하게 보존된다. `analysis`가 없으면 직렬화에서도 생략해 기존 이벤트의 영수증 해시를 유지한다. 기존 이벤트 재전송 시 원래 ID와 payload를 유지한다.

## 통계 방법과 표시 범위

### Sequential testing

변형 수 `K`, 사용자 수 `n >= 1`, 전체 오류 확률 `alpha = 0.05`에 대해 각 변형·표본 수의 오류 예산을 `alpha / (K*n*(n+1))`로 둔다. Hoeffding 부등식으로 전환율의 반경을 다음처럼 계산하고 `[0,1]`로 자른다.

```text
r(n) = sqrt(log(2*K*n*(n+1)/alpha) / (2*n))
변형별 구간 = [p_hat - r(n), p_hat + r(n)] ∩ [0,1]
대조군 대비 구간 = [L_treatment - U_control, U_treatment - L_control]
```

`sum(n>=1) 1/(n*(n+1)) = 1`이므로 모든 변형과 모든 표본 수에 대한 union bound가 0.05다. 반복 조회와 여러 대조군 대비 비교를 포함하는 보수적인 95% confidence sequence다. 표본이 없는 변형의 전환율 구간은 `[0,1]`이다. 효율적인 mixture 기반 검정을 구현한 것은 아니므로 필요한 표본 수가 클 수 있다. 자동 승자 선택이나 CUPED/세그먼트 유의성 판단에는 연결하지 않는다.

수식 근거: [CMU Hoeffding 강의](https://www.stat.cmu.edu/~cshalizi/sml/21/lectures/06/lecture-06.html), [NIST의 동시 구간/Bonferroni 설명](https://www.itl.nist.gov/div898/handbook/prc/section4/prc473.htm). Confidence sequence 배경: [Howard 외, time-uniform confidence sequences](https://arxiv.org/abs/1810.08240).

### Bayesian 분석

각 변형에 독립적인 `Beta(1,1)` 사전분포를 두고 전환 사용자 `s`, 전체 사용자 `n`에 대해 `Beta(s+1,n-s+1)` 사후분포를 사용한다. 사후 평균 차이는 해석적으로 계산한다. 고정 시드로 32,768쌍을 추출해 대조군보다 높을 확률, 차이의 95% 사후 구간, 해당 변형 선택 시 기대 손실 `E[max(p_control-p_treatment,0)]`을 표시한다. 동일 데이터의 조회 결과는 재현 가능하다.

확률의 최악 Monte Carlo 표준오차는 약 0.28pp다. 이는 추출 오차이며 데이터의 불확실성 자체는 사후 구간으로 표현한다. 사후확률은 p-value 또는 반복 검정의 위양성률 보장이 아니다. 사전분포 설정 UI와 자동 종료 정책은 제공하지 않는다.

### CUPED

대조군/처리군 각각의 평균을 뺀 사전 지표 `X`와 전환 `Y`로 공통 기울기를 구한다.

```text
theta = (Sxy_control + Sxy_treatment) / (Sxx_control + Sxx_treatment)
adjusted_difference = mean(Y_treatment) - mean(Y_control)
                      - theta * (mean(X_treatment) - mean(X_control))
```

각 비교의 전체 사용자에게 유효 baseline이 있고 각 군 30명 이상이며 사전 지표 분산이 있을 때 계산한다. 일부 사용자만 골라 계산하지 않는다. Welford 방식으로 공분산을 누적해 큰 baseline 상수에 대한 수치 안정성을 유지한다. 보정 전/후 근사 표준오차와 추정량 분산 감소도 표시한다. 보정값을 전환율 범위로 자르지 않으며 분산 감소가 음수일 수 있다.

표준오차는 같은 데이터에서 구한 기울기를 사용하는 진단용 근사치다. 별도의 유의성 판정·순차 CUPED 신뢰구간을 주장하지 않는다. 사전 변수와 효과 추정 분산 감소의 배경은 [Microsoft ExP 설명](https://www.microsoft.com/en-us/research/articles/deep-dive-into-variance-reduction/)을 참고한다.

### 세그먼트

사전 등록한 각 속성·값·변형별 관측 완료 사용자, 전환 사용자, CVR, 같은 집단 내 대조군 대비 차이를 표시한다. 속성 간 교차 조합이나 사후 집단 탐색 검정은 수행하지 않는다. 탐색용 기술 통계이며 집단별 유의성·인과 효과를 선언하지 않는다.

## 배포와 운영

1. Phase 4의 032까지 적용된 DB에서 API/Admin 쓰기를 중단하고 백업한다.
2. `prism-infrastructure/src/main/resources/migrations/033_advanced_analysis.sql`을 한 번 적용한다. 신규 DB는 최신 `schema.sql`을 사용한다. 자동 마이그레이션은 없다.
3. 모든 API 노드/Kafka worker와 Admin을 먼저 갱신한다. 구버전 수신 노드에 analysis를 보내면 필드가 유실될 수 있으므로 혼합 버전에서 기능을 활성화하지 않는다.
4. SDK/스타터 소비자를 갱신하고 새 실험에 분석 계획을 등록한 뒤 계측을 활성화한다. 기존 실험/이벤트를 자동 backfill하지 않는다.

Admin 작업자는 기본 30초 간격으로 관측·지연 기간이 끝난 사용자 최대 500명을 확정한다. 사용자별 짧은 트랜잭션과 행 잠금을 사용해 여러 Admin 인스턴스의 중복 작업을 방지한다. 적어도 하나의 Admin에서 `prism.analysis.finalization-enabled=true`가 필요하다. 간격은 `prism.analysis.finalization-interval-ms`로 조정한다. 처리량은 DB 작업 시간의 영향을 받으며 backlog가 생기면 화면 반영이 늦어진다.

```sql
SELECT COUNT(*) AS overdue, MIN(matures_at) AS oldest_due
FROM analysis_observations WHERE finalized_at IS NULL AND matures_at <= UTC_TIMESTAMP(6);
SELECT experiment_id, invalid_reason, COUNT(*) AS users
FROM analysis_observations WHERE invalid_reason IS NOT NULL
GROUP BY experiment_id, invalid_reason;
```

보고서는 1,000행씩 keyset 조회하고 누적 통계만 메모리에 보관한다. 임의 행 수 제한으로 표본을 잘라내지 않는다. 조회 비용은 확정 사용자 수에 비례하며 대규모 환경에서 응답 시간과 DB 부하를 관측해야 한다. 장기 보관/사전 집계/외부 웨어하우스 sink 연결은 별도의 운영 작업이다. 무효 결과를 숨기기 위해 DB 플래그만 지우지 말고 계측·지연 원인을 해결한 새 실험으로 다시 진행한다.

## 검증

```bash
./gradlew build
PRISM_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:33328/?allowPublicKeyRetrieval=true&useSSL=false' \
PRISM_TEST_MYSQL_USERNAME=root ./gradlew :prism-api:mysqlTest
PRISM_TEST_KAFKA_BOOTSTRAP=127.0.0.1:33329 ./gradlew :prism-api:kafkaTest
```

통계식 경계 조건, 재현 가능한 Bayesian 계산, CUPED 보정/누락 지표, 계획 잠금과 HTTP 검증, 결과 확정과 늦은 전환 차단, SDK 첫 메타데이터 보존 및 기존 이벤트 직렬화를 검증한다. MySQL은 신규/업그레이드 스키마와 UTC/Asia-Seoul JVM에서 식별자·마이크로초·0 baseline·중복 처리를 확인한다. Kafka 테스트는 HTTP 수신부터 warehouse 출력까지 메타데이터 일치를 확인한다. 별도 Java consumer는 Maven POM/Gradle module metadata 각각으로 SDK를 해석하여 새 API를 호출한다.

2026-09-14 개발 검증: JDK 21에서 전체 build, 일반 테스트 186개, 실제 MySQL 4개, Kafka 4.1 통합 테스트 1개, 별도 Java consumer 2개를 통과했다. MySQL/Kafka는 로컬 네이티브 프로세스로 실행했다. HTTP의 Jackson 3과 SDK/Kafka의 Jackson 2가 선택적 분석 필드 및 0 baseline을 동일하게 읽는 것도 확인했다.

## 개발 후 리뷰 수정

- **시각 정밀도 불일치:** 동일한 나노초 시각의 재노출이 DB 반올림 후 더 이른 노출로 오인되는 사례를 재현했다. 노출과 확정 후 전환의 시각을 저장된 로그에서 다시 읽어 DB 집계와 일치시켰다. H2 및 MySQL의 신규/업그레이드 스키마, UTC/Asia-Seoul에서 기간 경계와 재노출을 검증했다.
- **범위를 벗어난 사전 시각:** `Instant`가 허용하지만 UTC `LocalDateTime`으로 변환할 수 없는 연도를 입력하면 배치 처리가 예외로 중단됐다. SDK/API 공통 검증에서 미리 거부하며 HTTP 배치의 나머지 유효 이벤트는 계속 처리한다.
- **계획을 고정한 DRAFT 수정:** 계획 저장으로 설정 잠금이 생기면 설명 수정도 DRAFT 복귀로 간주해 거부했다. 노출 전 DRAFT 유지와 시작 후 DRAFT 복귀를 구분했다. 설명 저장과 UI 상태 선택을 검증하고, 목표 변경과 시작 후 DRAFT 복귀는 계속 거부되는 것을 확인했다.

추가 검증은 두 확정 작업자와 전환 수집의 동시 실행, MySQL 최초 노출 동시 삽입·재시도, 500명 확정 작업의 잔여 처리, 1,002명 보고서의 페이지 간 누락·중복을 포함한다. 순차 구간의 오류 예산 합, Bayesian 재현성, CUPED 누락/상수/큰 사전 값 처리, SRM·무효 데이터의 추론 차단, 계획 잠금, 이벤트 멱등성 및 Java 배포 호환성도 다시 확인했다.

2026-09-14 리뷰 후 검증: `build :prism-api:kafkaTest`와 `:prism-api:mysqlTest` 통과. 일반 테스트 192개, 실제 MySQL 4개, Kafka 1개, 별도 Java consumer 2개에서 실패·건너뜀이 없었다. 현재 변경 범위에서 확인한 리뷰 문제는 모두 수정했다.

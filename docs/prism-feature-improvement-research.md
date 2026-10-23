# Prism 기능 보강 조사

조사일: 2026-10-19 (한국 시간)

기준: `silbaram/prism`의 `main`, `2c54d57` — PR #39 병합 상태

현재 코드, 기존 성숙도 평가, GrowthBook·PostHog의 공식 문서와 Statsig의 공식 SDK 저장소를 대조했다. GitHub 원격 main도 같은 커밋임을 확인했다. 제품을 같은 환경에서 실행한 성능 비교나 요금제별 기능 검증은 수행하지 않았다. 이번 작업은 기능 조사이며 애플리케이션 코드 변경과 테스트 재실행은 포함하지 않는다.

## 결론

다음 개발의 중심은 **측정과 설정이 정상인지 확인할 수 있는 운영 기반**이다. 먼저 SDK·수집 진단과 설정 신선도 정책을 마련하고, 사용자 화면에서는 타기팅 편집기와 기존 시뮬레이터를 보강하는 순서를 권장한다.

앞서 제안한 타기팅 UI는 도입 편의성 측면에서 유효하다. 이번 코드 재확인에서는 설정의 무기한 유지, 메모리 이벤트 큐, 실제 타기팅을 제외하는 시뮬레이터를 확인했으므로 운영 기반의 우선순위를 더 높였다. 기능이 없는 경우, 일부 구현된 경우, 구현은 있으나 운영 검증이 필요한 경우를 구분한다.

## 이미 확보한 기반

| 영역 | 현재 구현 | 이번 조사에서의 판단 |
|---|---|---|
| 배정·출시 제어 | A/B/n, 결정적 배정, 참여율, 예약, 레이어, 전역 홀드아웃, 선택적 배정 유지 | 새로 만들기보다 장애 시 동작과 적용 확인을 보강 |
| 측정·귀속 | 평가와 노출 분리, 선행 노출 참조, 이벤트 ID·중복 처리, 고유 사용자 CVR | 수집 실패·지연·거절을 확인하는 기능 보강 |
| 분석 | SRM, 종료 후 전체 검정, 사전 분석 계획, 순차·베이지안, 제한적 CUPED·세그먼트 | 표본 설계·목표 지표 확장·판단 안내 보강 |
| 사용자 여정 | 순서형 퍼널, 저장된 퍼널, 타임라인, 경로 패턴과 사용자 조회 | 대량 조회와 결과 공유를 보강; 기본 여정 기능은 구현됨 |
| JVM 도입 | Spring 없는 기본 SDK, 선택적 SpEL 모듈, Spring 스타터, Java 소비자 검증 | 관리자 코드 생성과 배포·릴리스 흐름까지 독립 SDK 지원 확대 |
| 수집 파이프라인 | DIRECT/Kafka, inbox/outbox, 재시도, DLQ·warehouse 출력 | 존재하는 재처리 기반을 진단·운영 화면과 연결 |
| 관리자 보호 | ADMIN/VIEWER, CSRF, BCrypt, 변경 이력, 실험 설정 잠금 | 프로젝트·환경·팀 단위 운영으로 확장 필요 |

SRM이나 가드레일 이벤트, Kafka 재시도가 없다고 분류하면 기존 구현과 중복된다. 특히 가드레일 **등록·집계**와 가드레일 **악화 감지·알림·자동 중단**은 다른 기능이다.

## 공식 자료에서 확인한 비교 기준

| 제품·근거 | 확인한 기능 | Prism에 적용할 방향 |
|---|---|---|
| GrowthBook [타기팅][GB-TARGET] | 속성 이름·자료형·프로젝트 범위 정의, 조건 편집 | 속성 카탈로그와 타입에 맞는 조건 편집기 |
| GrowthBook [평가 진단][GB-DIAG] | SDK 평가 이벤트와 warehouse를 이용한 최근 규칙 동작 확인 | 규칙이 실제로 적용되는지 확인할 진단 경로. Prism의 큐·전송 진단 설계는 별도로 필요 |
| GrowthBook [검정력][GB-POWER], PostHog [표본·기간 계산][PH-POWER] | MDE·표본 수·실험 기간을 이용한 사전 설계 지원 | 분석 계획에 필요한 표본과 예상 기간을 연결 |
| GrowthBook [지표][GB-METRIC], PostHog [실험 지표][PH-METRIC] | 전환율 외 평균·비율·리텐션 등 지표 | 매출·횟수·지연시간을 측정할 지표 계약과 통계 확장 |
| GrowthBook [환경][GB-ENV], [게시·승인][GB-APPROVAL] | 환경별 SDK 연결과 키, 변경 버전·선택적 승인 흐름 | 개발/운영 분리, 키 범위, 변경 승인과 배포 이력 |
| PostHog [SDK 상태][PH-HEALTH] | 유입 이벤트의 SDK 종류·버전을 바탕으로 버전 노후화 표시 | SDK 종류·버전과 적용 상태를 운영자가 확인. 이 문서가 큐 유실 진단까지 제공한다는 뜻은 아님 |
| PostHog [안정적인 식별자][PH-ID] | 익명→로그인 전환으로 해시 입력이 바뀌는 문제와 대응 | 다른 플랫폼 SDK 전에 사용자 식별·배정 유지 계약 정립 |
| Statsig [공식 SDK 저장소][ST-SDK] | JavaScript 클라이언트, 기기 내 평가, React·React Native 연동 | JVM 이후 브라우저·React 도입 범위 확장 |

비교 제품의 속성 자료형과 연산자를 그대로 복사하지 않는다. Prism은 현재 `prism:v1:` 평가기가 실제로 지원하는 범위부터 화면에 제공해야 한다. 공개 문서의 기능 존재와 Prism에서 검증해야 할 구현 방식·성능도 구분한다.

## 보강 목록과 우선순위

P0는 제한된 사내 운영에서도 먼저 확보할 기반, P1은 실험 운영과 도입 편의성 개선, P2는 사용 범위 확대 단계다. 데이터 보존·환경 분리는 사용 규모나 팀 수가 커지기 전에 앞당긴다.

| 우선순위 | 보강 항목 | 현재 공백 | 개발 완료 기준 |
|---|---|---|---|
| P0 | SDK·수집 진단 | 큐 크기·노출 중복 제거·타기팅 오류는 조회 가능하나 설정·전송·파이프라인 상태의 통합 진단 부족 | 설정 신선도, 전송 성공·거절·재시도, 큐 사용량, DB 반영 지연을 구분해 확인 |
| P0 | 설정 신선도·장애 정책 | 마지막 정상 설정에 최대 유지 시간 없음 | 설정 노후화를 탐지하고, 정책에 따라 기존 설정 유지 또는 새 배정 차단; 실제 중단 전파 시간을 검증 |
| P0 | CI·업그레이드·릴리스 자동화 | 별도 MySQL/Kafka 테스트는 존재하지만 저장소 CI 설정 없음; DB 변경은 수동 SQL 적용 | 기본 테스트·SDK 게시 검증 자동 실행, 실제 DB/브로커 검증 경로, 기존 DB 업그레이드 기록과 실패 복구 절차 |
| P1 | 타기팅 편집·정확한 미리보기 | 문자열 직접 입력; 시뮬레이터는 타기팅과 저장된 최초 배정을 평가하지 않음 | 타입에 맞는 조건 작성, 규칙 왕복 변환, 샘플 속성으로 동일 평가기 검증, 미리보기 범위 명시 |
| P1 | 프레임워크별 도입 가이드 | 기본 SDK는 독립적이나 관리자 생성 예제는 `@Service` 중심 | 일반 Java/Kotlin과 Spring을 선택해 실행 가능한 초기화·노출·전환·종료 예제 제공 |
| P1 | 실험 설계 지원 | 관측 기간·대조군 등 분석 계획은 있지만 MDE·검정력·필요 표본 설계 없음 | 기준 전환율·MDE·검정력·배정 비율로 필요 표본과 예상 기간 산출, 분석 방식별 가정 표시 |
| P1 | 지표 모델 확장 | 전환 여부가 목표 지표의 중심; 확장 필드가 있어도 숫자형 목표의 집계·추론 계약은 없음 | 사용자당 매출·횟수 등 평균 지표부터 지원, 단위·결측·미발생·귀속·관측 구간을 정의 |
| P1 | 가드레일 알림 | 이벤트 등록과 분류·집계는 있으나 악화 기준·알림·중단 정책 없음 | 방향·허용 악화 폭·최소 데이터 기준으로 경고; 수동 중단과 감사 이력 연결 |
| P1 | 프로젝트·환경·키 범위 | 공통 SDK 역할과 정적 키 목록, 관리자/조회자 계정 중심 | 설정·수집·집계·여정·재처리까지 환경별 격리, 범위 있는 키와 사용자 권한 |
| P1, 규모 확대 전 | 데이터 수명·조회 규모 | 자동 보존·사용자 삭제 흐름 부족; 경로 조회에 이벤트·경로 개수 한도 | 보존·삭제·재유입 정책, 백필 가능한 집계, 한도 초과 시 부분 결과를 완전한 결과로 표시하지 않음 |
| P2 | QA 배정·보고서·반복 실험 | 시뮬레이터는 있지만 실제 환경의 변형별 QA와 분석 조건을 고정한 최종 보고서가 부족 | QA 트래픽을 본 실험 측정에서 구분, CSV·고정 보고서, 초안 복제·템플릿 제공 |
| P2 | 브라우저·React SDK와 식별자 계약 | JVM SDK 중심, 익명·로그인 연결 미지원 | 환경별 클라이언트용 키, 공통 배정 기준 데이터, 노출·재시도·식별자 전환·배정 유지 계약 검증 |

### 첫 개발 묶음: 진단과 설정 신선도

SDK에서 하나의 불변 진단 스냅샷을 조회하는 형태로 시작한다. 일반 JVM 소비자가 사용할 인터페이스를 먼저 만들고 Spring 모니터링 연동은 어댑터로 제공한다.

- 설정 버전, 마지막 검증된 서버 응답 시각, 최근 동기화 결과·연속 실패, 초기화 상태, 타기팅 거절 실험 수.
- 큐 크기·용량·가장 오래 대기한 시간, 마지막 배치 전송 결과, 서버 상태별 이벤트 수와 용량 거절 수.
- 기존 노출 중복 제거 사용량·거절 카운터와 SDK 버전·인스턴스 정보를 같은 진단에서 확인.
- 수집 서버의 inbox/outbox backlog, 가장 오래된 대기 시간, 재처리·DLQ 건수를 별도로 수집.

SDK의 큐 등록, Kafka의 `QUEUED`, DB의 `ACCEPTED`/`DUPLICATE`를 같은 성공 수치로 합치지 않는다. 서버 ACK를 받았어도 비동기 materialization이 끝나지 않았을 수 있다. 원본 사용자 속성·타기팅 규칙·사용자 ID는 진단 전송의 기본 항목에 넣지 않는다. 통신이 끊긴 SDK는 중앙 화면에서 정상으로 가정하지 않고 마지막 관측 시각과 미확인 상태를 표시한다.

설정 정책은 기존 설정 유지와 기한 초과 시 새 배정 차단을 명시적으로 선택하게 한다. 실패 결과에 대한 서비스 기본 경험은 호출자가 정한다. 이미 발생한 정상 노출의 지연 전환은 기존 귀속 계약을 고려해 별도로 다룬다. 정상 `304` 등 동일 버전 응답도 서버 확인으로 기록하되, 메모리에 있는 설정을 읽는 동작이 신선도를 갱신하지 않게 한다. 호환성을 위해 신규 정책의 기본값·활성화 시점을 문서화한다.

**검증 완료 기준:** 초기화 전, 정상 동기화, `304`, 잘못된 설정, 통신 단절, 복구, 큐 포화, 부분 ACK, 재시작을 재현한다. DIRECT와 Kafka 경로를 구분하고, 연결 단절 중 일시중지한 실험이 언제까지 배정되는지 설정 정책과 일치해야 한다. 운영 임계값은 예상 사용자 수·허용 전파 시간·유실 기준에 맞춰 결정한다.

이 단계에서 영속 큐까지 한 번에 구현하지 않는다. 진단으로 유실·지연 규모를 확인하고 허용 유실 기준에 따라 별도 개발한다. 영속 큐를 도입할 때는 ACK 전 제거 금지, 원래 이벤트 ID 유지, 디스크 한도, 재시작 재전송을 함께 설계한다.

### 다음 사용자 화면: 타기팅 편집과 시뮬레이터 보강

예시 흐름: 초안 실험 → `age ≥ 20 AND country = KR` 작성 → 샘플 속성 입력 → 대상 여부·예상 변형·판정 이유 확인 → 저장.

- 속성 카탈로그에 이름·설명·자료형을 등록하고, 문자열·숫자·불리언과 현재 지원하는 연산자부터 제공한다.
- 편집기는 `prism:v1:` 규칙을 생성한다. 목록·그룹·깊이 제한과 숫자 정밀도는 코어 평가기 계약을 따른다.
- 편집기와 고급 입력을 오갈 때 의미를 보존하고, 해석할 수 없는 기존 규칙을 덮어쓰지 않는다. SpEL은 고급 입력과 호환 모듈의 기존 권한 경계를 따른다.
- 미리보기는 같은 타기팅 평가기로 샘플 속성을 평가한다. 타기팅 대상 여부와 기간·참여율·레이어·홀드아웃에 따른 최종 참여 여부를 구분한다.
- 초안 가정의 미리보기와 실제 상태를 반영한 점검을 구분한다. 배정 유지 저장소까지 조회하지 않는 경우 그 범위를 표시하고, 실제 노출·전환은 만들지 않는다.
- 시작·예약·분석 계획 등록으로 잠긴 설계는 기존 변경 제한을 유지한다. 편집기를 추가했다고 실행 중 실험을 재설계할 수 있게 하지 않는다.

**검증 완료 기준:** 화면에서 만든 규칙과 원래 JSON 규칙의 평가가 같고, 누락·자료형 오류·`not`·숫자 경계·중첩·미지원 버전에서 차이가 없어야 한다. 현재 시뮬레이터가 타기팅을 생략한다는 안내도 새 동작에 맞게 수정한다.

### 실험 설계·지표·판단 흐름

표본 계산은 우선 두 집단의 전환 여부를 비교하는 명시적인 설계부터 지원한다. 절대 효과(%p)와 상대 효과(%)를 구분하고, 참여율·변형별 비중·하루 적격 사용자 수를 반영한다. 고정 표본 설계 값을 순차 분석이나 베이지안 분석의 종료 보장으로 그대로 사용하지 않는다. 여러 변형·여러 지표의 비교와 요일 효과·관측 완료 기간도 별도로 설명한다.

수치형 지표는 이벤트에 숫자 하나를 추가하는 작업으로 끝나지 않는다. 사용자 단위 집계, 노출 이후 관측 구간, 미발생 사용자, 단위·통화, 중복·재시도, 극단값 처리와 분석 계획 버전을 함께 정의해야 한다. 매출 합계/구매 수 같은 비율은 사용자별 비율의 평균과 다른 지표이므로 분모·공분산을 고려한 별도 추론이 필요하다. 기존 전환율의 신뢰구간과 순차·베이지안 계산을 새 지표에 그대로 재사용하지 않는다.

가드레일은 알림과 수동 중단부터 도입한다. 반복 조회에 대한 통계적 동작과 최소 데이터 기준을 검증하기 전 자동 중단을 기본 제공하지 않는다. 최종 보고서는 분석 계획·설정 버전·데이터 마감·관측 완료 기준과 결과를 고정해 재현할 수 있게 한다. 기존의 실시간 분석 캐시는 최종 보고서 보관과 다른 기능이다.

### 팀·플랫폼 확장의 선행 조건

프로젝트와 환경 차원은 설정 API뿐 아니라 이벤트의 실험 참조, 배정 유지, 캐시, 분석, 여정 조회, Kafka 재처리에 동일하게 적용해야 한다. 화면에 환경 선택만 추가해서는 격리가 완성되지 않는다. 현재 서버용 API 키를 브라우저에 배포하는 방식으로 SDK를 확장하지 않고, 공개 가능한 설정 읽기와 제한된 수집 권한을 별도로 설계한다.

익명 ID와 로그인 ID를 단순히 합친다고 실험 배정이 유지되지는 않는다. 어떤 식별자로 해시하고 충돌 시 어느 노출에 귀속하는지 정해야 한다. 브라우저·React SDK는 공통 해시·UTF-8·숫자 비교·타임스탬프·규칙 의미의 기준 데이터로 JVM과 같은 결과를 검증한다. 형식 있는 feature flag·동적 설정값은 실험 변형·목표 지표와 구분한 별도 기능으로 검토한다.

보존·삭제에는 전환의 노출 참조, 중복 제거 영수증, 지연 전환·Kafka 재시도 기간과 삭제 이벤트 재유입을 함께 고려한다. 경로 집계의 현재 한도는 제거하기보다 먼저 대표 데이터에서 조회 부하를 측정하고, 필요하면 사전 집계·비동기 보고서로 확장한다.

## 권장 착수 순서

| 순서 | 개발 묶음 | 선행 조건·결과 |
|---|---|---|
| 1 | JVM SDK 진단 계약 + 설정 신선도 정책 + 회귀 CI | 설정·이벤트 상태를 관측하고 실패 동작을 재현 |
| 2 | 수집 서버 진단 + 관리자 적용 확인 + 일반 JVM 예제 | 개발자가 코드 적용 후 측정 경로를 확인 |
| 3 | 속성 카탈로그 + 타기팅 편집기 + 기존 시뮬레이터 개선 | 관리자가 지원되는 조건을 작성·검증 |
| 4 | 표본 설계 + 수치형 지표 + 가드레일 알림 + 고정 보고서 | 실험 계획부터 판단까지 연결 |
| 5 | 환경·권한·데이터 정책 + 브라우저/React SDK | 여러 팀·플랫폼으로 확장 |

MySQL/Kafka 통합 검증과 DB 업그레이드 자동화는 첫 단계부터 병행한다. 이미 제공하는 opt-in 테스트를 CI에 연결하고 신규 DB와 기존 DB의 업그레이드를 모두 검증한다. 자동 마이그레이션 도입 시 기존 설치 baseline과 적용 이력, MySQL DDL의 중간 실패 복구를 먼저 정의한다. 운영 전파 목표·처리량·허용 유실량은 사용 환경 없이 수치로 단정하지 않는다.

## Prism 코드 근거

| 근거 | 확인 내용 |
|---|---|
| [SDK 옵션](../prism-sdk/src/main/kotlin/io/github/silbaram/prism/sdk/PrismClientOptions.kt) | 큐·타임아웃·동기화·평가기 설정, 최대 설정 노후화 정책 없음 |
| [로컬 SDK](../prism-sdk/src/main/kotlin/io/github/silbaram/prism/sdk/LocalEvaluationClient.kt#L92) | 설정 유지·갱신, 메모리 큐, ACK·거절·재시도 처리 |
| [공개 SDK 진단](../prism-sdk/src/main/kotlin/io/github/silbaram/prism/sdk/PrismClient.kt#L108) | refresh, 큐 크기, 타기팅 오류·중복 제거 카운터가 이미 존재 |
| [시뮬레이터](../prism-admin/src/main/kotlin/io/github/silbaram/prism/admin/service/SimulatorService.kt#L21)·[화면 안내](../prism-admin/src/main/resources/templates/simulator/index.html) | 빈 타기팅 규칙·빈 사용자 속성 사용, 실제 상태·저장 배정과의 차이를 안내 |
| [타기팅 입력](../prism-admin/src/main/resources/templates/experiment/form.html#L169)·[규칙 계약](framework-independent-sdk.md) | 문자열 입력, 독립 규칙과 기존 SpEL 지원 범위 |
| [코드 생성](../prism-admin/src/main/kotlin/io/github/silbaram/prism/admin/service/SdkGuideService.kt) | Java/Kotlin 예제가 Spring 서비스와 주입을 전제 |
| [분석 계획](../prism-admin/src/main/kotlin/io/github/silbaram/prism/admin/service/AnalysisPlanService.kt#L12)·[고급 분석](../prism-admin/src/main/kotlin/io/github/silbaram/prism/admin/service/AdvancedAnalysisService.kt) | 관측 기간·대조군·CUPED·세그먼트와 SRM 기반 추론 차단 구현 |
| [이벤트 집계](../prism-admin/src/main/kotlin/io/github/silbaram/prism/admin/service/AnalyticsService.kt#L46)·[이벤트 계약](../prism-common/src/main/kotlin/io/github/silbaram/prism/common/rest/dto/event/Events.kt) | 전환·가드레일 이벤트 집계, 확장 필드와 숫자형 목표 지표 계약의 차이 |
| [파이프라인](../prism-api/src/main/kotlin/io/github/silbaram/prism/api/pipeline/PipelineStorage.kt) | inbox 재시도·DLQ·outbox ACK 처리 기반 존재 |
| [API 인증](../prism-api/src/main/kotlin/io/github/silbaram/prism/api/security/ApiSecurityConfiguration.kt#L24)·[관리자 인증](../prism-admin/src/main/kotlin/io/github/silbaram/prism/admin/security/AdminSecurityConfiguration.kt#L17) | 환경별 범위 없는 SDK 키 목록, 관리자·조회자 계정 |
| [경로 집계](../prism-admin/src/main/kotlin/io/github/silbaram/prism/admin/service/JourneyPatternService.kt#L114) | 최대 이벤트 200,000개, 서로 다른 경로 5,000개 제한 |
| [실환경 테스트 작업](../prism-api/build.gradle.kts#L12)·[DB 업그레이드](../README.md#db-업그레이드) | MySQL/Kafka 테스트는 일반 test와 별도, DB 변경은 수동 적용 |

이번 조사 시점에 추적된 `.github` 등의 CI 설정과 열린 GitHub 이슈는 없었다. 기존 테스트가 제공된다는 사실과 해당 환경에서 운영 검증을 수행했다는 사실은 구분한다. 이번 조사는 테스트를 실행하지 않았으며, 이전 결과와 제한은 [기존 성숙도 평가](prism-maturity-comparison.md)와 [독립 SDK 검증 문서](framework-independent-sdk.md)에 기록되어 있다.

## 공식 자료와 확인 범위

아래 링크는 조사 시 읽은 공식 저장소의 커밋으로 고정했다. GrowthBook `0d70056`, PostHog `7e3f8f8`, Statsig `4fd2142` 기준이다. GrowthBook·PostHog는 문서 내용, Statsig는 SDK 제공 범위만 비교 근거로 사용했다. 최신 배포 제품·요금제의 동일 지원 여부나 성능 우열까지 주장하지 않는다.

- GrowthBook: [타기팅][GB-TARGET], [평가 진단][GB-DIAG], [검정력][GB-POWER], [지표][GB-METRIC], [환경][GB-ENV], [게시·승인][GB-APPROVAL].
- PostHog: [실험 지표][PH-METRIC], [표본·기간 계산][PH-POWER], [SDK 상태][PH-HEALTH], [안정적인 식별자][PH-ID].
- Statsig: [JavaScript SDK 및 React 계열 패키지][ST-SDK].

[GB-TARGET]: https://github.com/growthbook/growthbook/blob/0d70056f593d8d79d367b17f29e7b151b6e3566f/docs/features/targeting.mdx
[GB-DIAG]: https://github.com/growthbook/growthbook/blob/0d70056f593d8d79d367b17f29e7b151b6e3566f/docs/features/diagnostics.mdx
[GB-POWER]: https://github.com/growthbook/growthbook/blob/0d70056f593d8d79d367b17f29e7b151b6e3566f/docs/statistics/power.mdx
[GB-METRIC]: https://github.com/growthbook/growthbook/blob/0d70056f593d8d79d367b17f29e7b151b6e3566f/docs/app/metrics.mdx
[GB-ENV]: https://github.com/growthbook/growthbook/blob/0d70056f593d8d79d367b17f29e7b151b6e3566f/docs/features/environments.mdx
[GB-APPROVAL]: https://github.com/growthbook/growthbook/blob/0d70056f593d8d79d367b17f29e7b151b6e3566f/docs/features/publishing-and-approval-flows.mdx
[PH-METRIC]: https://github.com/PostHog/posthog.com/blob/7e3f8f8caef089374fa5596c699e21b4119f2cb8/contents/docs/experiments/metrics.mdx
[PH-POWER]: https://github.com/PostHog/posthog.com/blob/7e3f8f8caef089374fa5596c699e21b4119f2cb8/contents/docs/experiments/sample-size-running-time.mdx
[PH-HEALTH]: https://github.com/PostHog/posthog.com/blob/7e3f8f8caef089374fa5596c699e21b4119f2cb8/contents/docs/health-checks/sdk-health.mdx
[PH-ID]: https://github.com/PostHog/posthog.com/blob/7e3f8f8caef089374fa5596c699e21b4119f2cb8/contents/docs/feature-flags/stable-identity-for-flags.mdx
[ST-SDK]: https://github.com/statsig-io/js-client-monorepo/blob/4fd2142d93886403b1c7ffeb75bd04c933e8e49d/README.md

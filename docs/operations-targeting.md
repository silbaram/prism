# 수집 진단과 타기팅 편집

## 적용 순서

기존 DB는 API/Admin/worker 쓰기를 중단하고 백업한 뒤 [038 마이그레이션](../prism-infrastructure/src/main/resources/migrations/038_pipeline_diagnostics_targeting.sql)을 한 번 적용한다. 신규 DB에는 갱신된 `schema.sql`을 사용한다. API와 Admin을 함께 갱신한다. 과거 outbox의 생성 시각은 임의로 채우지 않으며 화면에 미기록 건수로 표시한다.

## 수집 상태 연결

API에는 다음 설정을 주입한다.

```text
PRISM_MONITORING_KEYS=<전용 모니터링 키; 쉼표로 여러 키 지정 가능>
```

Admin에는 다음 설정을 주입한다.

```text
PRISM_MONITORING_API_URL=http://prism-api:8080
PRISM_MONITORING_API_KEY=<위 모니터링 키 중 하나>
```

주소는 배포 환경의 API 주소로 바꾸며 context path가 있으면 포함한다. 키는 32–512자의 공백 없는 ASCII 값으로 안전하게 생성·주입한다. SDK용 `PRISM_API_KEYS`와 다른 값을 사용한다. 모니터링 키는 SDK 설정·이벤트 API에 접근할 수 없고 SDK 키로 진단 API에 접근할 수 없다. 네트워크를 통과하는 운영 연결은 HTTPS 또는 보호된 내부 연결을 사용한다.

관리자 메뉴 **수집 상태**(`/admin/pipeline`)는 API의 `/api/internal/pipeline/diagnostics`에서 조회한 집계만 표시한다. ADMIN과 VIEWER 모두 읽을 수 있다. 페이지 진입·새로고침마다 조회하며 자동 폴링이나 SDK 중앙 보고를 수행하지 않는다. 미설정·인증 오류·연결 실패·잘못된 응답은 미확인으로 표시하며 마지막 조회 성공 시각만 남긴다. 이전 성공의 건수를 현재 상태로 재사용하지 않는다. 요청은 전체 응답 수신을 포함해 최대 약 3초 대기하고 응답은 64KiB로 제한한다.

| 항목 | 해석 |
|---|---|
| API 모드·소비 작업 설정 | 연결한 한 API 노드의 DIRECT/KAFKA 설정. 브로커나 별도 worker의 생존 증명은 아님 |
| inbox 처리 대기·시각 도래 | 영속 접수 후 DB 반영 대기와 현재 retryAt이 도래한 건수 |
| 처리 시도 이력이 있는 대기 | attempts > 0인 대기 행. 최초 처리·진행 중인 작업도 포함하므로 실패 누계와 다름 |
| 최장 대기 | PENDING 행의 생성 시각부터 경과한 초. 큐가 비어 있으면 값 없음 |
| 완료·거절 | 보존 중인 DONE/REJECTED inbox 행 수. 전체 접수 누계나 DIRECT 처리 누계가 아님 |
| Warehouse·DLQ 대기 | 아직 전송되지 않은 outbox 행 수. 전송 후 제거되므로 전송 누계와 다름 |
| outbox 시각 미기록 | 업그레이드 전 생성된 행 등 생성 시각을 모르는 건수. 알려진 행의 최장 대기와 함께 해석 |

사용자 ID·이벤트 ID·원본 payload·키·브로커 주소는 응답에 넣지 않는다. Kafka 접수(`QUEUED`)와 최종 DB 반영을 합산하지 않는다. Kafka broker lag, 이미 전송된 DLQ의 내용·누계, 경고 임계값·알림, 여러 API의 상태 통합은 후속 범위다.

## 타기팅 속성과 조건 편집

1. **타기팅 속성**(`/admin/targeting`)에서 속성 이름·자료형·설명을 등록한다. ADMIN만 등록할 수 있고 VIEWER는 조회만 한다. 자료형은 문자열·숫자·불리언이다. 기존 이름을 다시 등록해 자료형을 덮어쓰지 않는다.
2. 초안 실험의 생성·수정 화면에서 **규칙 추가 → 조건 편집**을 선택한다. 등록된 속성과 자료형을 참고해 조건을 작성한다. 카탈로그는 편집 도움이며 기존 SDK 속성이나 고급 규칙의 런타임 자료형을 강제로 변환하지 않는다.
3. `같음`, `다름`, 숫자 대소 비교, `목록에 포함`, `존재함`과 중첩 AND·OR·NOT을 사용할 수 있다. 여러 최상위 규칙은 AND로 평가한다. 목록은 JSON 형식으로 입력한다.
4. **고급 입력**에서 `prism:v1:` 원문을 확인·편집할 수 있다. SpEL·미지원 버전·혼합 자료형 목록처럼 시각 편집기가 해석하지 못하는 입력은 원문을 유지한다. 시각 편집기의 숫자는 JavaScript 부동소수점으로 변환하지 않아 큰 정수·긴 소수의 값을 보존한다. 최종 문법·숫자 범위 검증은 기존 코어 평가기가 수행한다.
5. 샘플 속성 JSON을 입력하고 **타기팅 확인**을 누르면 저장 전 규칙별 일치 여부를 확인한다. 누락·자료형 불일치·숫자 비교·그룹 판정은 실제 `RuleEvaluator`를 사용한다. 잘못된 JSON·미지원 규칙은 실패로 표시한다. 실험 저장이나 노출·전환 기록은 만들지 않는다.

조건 값에는 줄바꿈을 직접 입력할 수 있다. 기존 문자열을 편집하지 않으면 LF·CRLF·CR을 원문대로 보존하며, 다른 조건 수정이나 고급 입력 왕복도 값을 변경하지 않는다. 줄바꿈이 포함된 속성 이름은 고급 입력에서 원문을 유지한다.

시작·예약·분석 계획 등 기존 조건으로 잠긴 설정에는 시각 편집기를 제공하지 않는다. 서버의 기존 설계 변경 제한도 유지한다.

## 시뮬레이터

저장된 실험은 `/admin/simulator`에서 User ID와 샘플 속성 JSON을 입력해 확인한다.

- **현재 상태 점검**: ACTIVE 상태, 기간, 홀드아웃, 레이어, 참여율, 실제 타기팅을 반영한다.
- **설계 미리보기**: 실험 상태만 제외하며 기간 등 다른 참여 조건은 그대로 적용한다.
- 결과는 타기팅 일치·불일치와 최종 참여 여부·예상 변형·제외 이유를 구분한다.
- 저장된 최초 배정과 SDK별 배정 저장소는 조회하지 않는다. 배정 유지가 설정된 실험에는 실제 유지 변형과 다를 수 있음을 표시한다.
- ADMIN과 VIEWER가 사용할 수 있으며 CSRF 보호를 유지한다. 샘플 속성은 저장하지 않고 노출·전환을 기록하지 않는다.

샘플 JSON의 정수는 범위에 따라 `Int`·`Long`·`BigInteger`, 소수는 정밀도를 보존하는 `BigDecimal`로 평가한다. SpEL은 JVM 숫자형에 따라 연산 결과가 달라지므로 SDK에서 다른 숫자형을 명시적으로 사용하는 경우 이 매핑과 차이가 날 수 있다. 기본 `prism:v1:` 숫자 비교는 이들 숫자형의 값을 같은 수치로 비교한다.

일반 JavaScript 검증은 `node --test prism-admin/src/test/js/*.test.cjs`, 서버 검증은 `./gradlew :prism-api:test :prism-admin:test`로 실행한다. 실제 DB 업그레이드와 Kafka 검증은 기존 opt-in `mysqlTest`·`kafkaTest` 작업을 사용한다. 이번 변경에는 GitHub Actions CI 자동화가 포함되지 않는다.

DOM에서 발생하는 문자열 정규화 회귀는 `node --test prism-admin/src/test/browser/targeting-editor.cjs`로 별도 검증한다. 이 명령은 로컬 Playwright 패키지와 Chromium이 필요하며, 브라우저 경로는 `PRISM_TEST_CHROMIUM_PATH`로 지정할 수 있다. 기본 경로는 `/usr/bin/chromium`이다.

## 구현 검증 결과

2026-10-23 (한국 시간), `feature/pipeline-monitoring-targeting`의 로컬 변경에서 확인했다.

| 검증 | 결과 |
|---|---|
| 일반 회귀 | API 47건, Admin 140건 통과, 실패·오류·건너뜀 0건 |
| 마지막 화면 보강 후 관련 회귀 | 타기팅·시뮬레이터·수집 화면·권한 5건 재검증 통과 |
| JavaScript | 숫자 정밀도·중첩 규칙·고급 입력 보존 및 기존 이벤트 선택기, 7건 통과 |
| 실제 MySQL 8.0.44 | 신규 스키마·기존 DB 업그레이드 × UTC/Asia/Seoul, 4건 통과 |
| 실제 Kafka 4.1.0 | 처리·중복 제거·종료 중 저장·재시작, 2건 통과 |
| 패키징 | API/Admin 실행 JAR 빌드 성공 |
| 실제 브라우저 | 실제 MySQL에 연결한 API/Admin에서 속성 등록 → 조건 작성 → 저장 전 평가 → 저장·재조회 → 시뮬레이터 → ACTIVE 설정 잠금 확인 |
| 장애·복구 화면 | Kafka 모드 API 조회 성공 → API 프로세스 종료 시 미확인·이전 수치 숨김 → DIRECT 모드 API 재시작 후 모드 표시 갱신 |
| 미리보기 부작용 | 설정 작업·미리보기 후 실제 MySQL 노출·전환 행이 각각 0건임을 확인 |
| 정리 | 검증용 API/Admin 프로세스·MySQL/Kafka 컨테이너 제거 |

Chromium에서 `/prism` context path를 사용해 AJAX·폼·정적 파일 경로도 확인했다. 큰 정수 `9007199254740993`과 기존 SpEL을 저장·재조회해 원문 값을 확인했다. 브라우저의 일부 미리보기 응답을 의도적으로 지연시켜 이전 요청의 결과가 최신 결과를 덮어쓰지 않는지도 검증했다. Bootstrap CSS는 기존 로컬 사본으로 제공했으므로 외부 CDN의 가용성을 검증한 것은 아니다.

임시 근거 파일은 `/tmp/prism-monitoring-regression.log`, `/tmp/prism-monitoring-ui-final.log`, `/tmp/prism-monitoring-js-final.log`, `/tmp/prism-operations-infra-tests.log`, `/tmp/prism-operations-verification-final.log`에 있다. 초기 화면 보강 때 Admin의 테스트 XML은 관련 5건 결과로 갱신됐으며, 아래 리뷰 수정 검증에서는 전체 144건 결과로 갱신됐다. 화면 캡처는 `/tmp/prism-pipeline-available.png`, `/tmp/prism-pipeline-unavailable.png`, `/tmp/prism-pipeline-direct.png`, `/tmp/prism-targeting-editor.png`, `/tmp/prism-targeting-simulator.png`에 있다. 임시 파일은 이 클라우드 환경의 수명에 종속된다.

단일 DB·단일 브로커의 기능·장애 재현 범위이며 운영 부하, 다중 브로커 장애, DB 자체 장애 주입, 운영 데이터 마이그레이션은 수행하지 않았다.

## 리뷰 후 수정·재검증

같은 날 리뷰에서 재현한 두 문제를 수정했다.

- 단일 행 입력란이 문자열의 줄바꿈을 제거하던 문제: 조건 값 입력을 textarea로 바꾸고, 브라우저가 CR·CRLF를 LF로 정규화하더라도 편집하지 않은 원래 값을 보존한다. 단일 행으로 표시할 수 없는 속성 이름은 고급 입력으로 유지한다.
- 정수까지 `BigDecimal`로 바꾸면서 SpEL 결과가 달라지던 문제: 정수를 범위에 맞는 JVM 정수형으로 유지한다. 예를 들어 `age=29`의 `age / 10 == 2`가 `Int`를 사용하는 SDK 경로와 미리보기에서 모두 참으로 평가된다. 큰 정수와 긴 소수의 정밀도 검증은 유지한다.

회귀 테스트를 먼저 추가했을 때 수정 전에는 JVM 테스트 4건 중 3건과 DOM 테스트 6건 모두가 실패했다. 수정 후 다음 명령으로 Admin 전체 144건과 JavaScript·Chromium DOM 13건이 통과했다. 실패·오류·건너뜀은 0건이며 Admin 실행 JAR 빌드도 성공했다.

```bash
./gradlew :prism-admin:test :prism-admin:bootJar --offline --max-workers=4
node --test prism-admin/src/test/browser/targeting-editor.cjs prism-admin/src/test/js/*.test.cjs
```

새 JVM 테스트는 정수 경계·SpEL 산술·정밀한 숫자 비교·숫자 범위를 검증하고, DOM 테스트는 LF·CRLF·CR 보존, 다른 조건 수정, 문자열 편집과 고급 입력 왕복, 줄바꿈이 있는 속성 이름의 원문 유지를 검증한다. HTTP 미리보기에서도 정수 SpEL 판정을 확인했다. 수정 전 근거는 `/tmp/prism-targeting-review-repro-jvm.log`와 `/tmp/prism-targeting-review-repro-browser.log`, 수정 후 근거는 `/tmp/prism-targeting-review-fixed-jvm.log`와 `/tmp/prism-targeting-review-fixed-browser.log`에 있다.

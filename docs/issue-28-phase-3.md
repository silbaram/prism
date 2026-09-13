# #28 Phase 3 — 운영 기능

## 구현 범위

| 항목 | 동작 |
|---|---|
| 참여 비율 | 0–100%, 변형 분할과 별도 해시. 시작 후 확대만 허용 |
| 실험 기간 | UTC 시작·종료 시각, SCHEDULED 자동 시작, ACTIVE/PAUSED 자동 종료, SDK 자체 기간 검사 |
| 인증·권한 | Admin 로그인·세션·CSRF, 관리자/조회자 역할, 모든 SDK API의 헤더 키 인증 |
| 지표 정의 | 목표 / 가드레일 / 보조 이벤트 구분, 가드레일 설정과 이력 |
| Admin 검증 | 참여 비율·기간·가드레일·설명 길이·SpEL 문법 검증, 400/404/409 응답 |

## 실험 참여와 기간

`trafficAllocation`은 전체 사용자 중 실험에 참여할 비율입니다. 변형 가중치 합계는 여전히 100입니다. 예를 들어 참여 10%, A/B 가중치 50/50이면 전체 사용자의 약 5%씩 A/B에 참여합니다. 참여 해시는 기존 변형 해시와 분리하며, 기본값 100%에서 기존 변형 배정은 유지됩니다. 참여율을 5→25→100%로 확대해도 이미 참여한 사용자의 변형은 바뀌지 않습니다. 실험 시작·예약 후 축소는 차단합니다.

미참여·기간 밖·타기팅 제외는 변형이 없는 결과이며 노출을 등록하지 않습니다. LOCAL과 REMOTE 모두 `variant=null`, 기존 `9000` 응답 코드를 사용합니다. 비참여 여부를 확인하기 위해 노출이 생기는 원격 assign을 반복 호출할 필요는 없습니다.

Admin 시각 입력은 **UTC**이며 시작은 포함하고 종료는 제외합니다 (`startsAt <= now < endsAt`). 빈 시각은 제한 없음입니다. 시각은 마이크로초 정밀도와 MySQL TIMESTAMP 범위를 검증합니다.

- 새 실험은 DRAFT로 저장합니다. 미래 시작 시각을 입력한 뒤 수정 화면에서 SCHEDULED로 저장하면 예약됩니다.
- Admin 프로세스가 기본 5초마다 예약을 처리합니다. `prism.schedule.interval-ms`로 주기를 조정합니다. 여러 Admin 프로세스의 동일 작업은 DB 행 잠금과 상태 재확인으로 한 번만 기록합니다.
- SCHEDULED는 시작 시각 이후 ACTIVE가 됩니다. 이미 종료 시각까지 지났으면 ACTIVE를 거치지 않고 ENDED가 됩니다. PAUSED도 종료 시각에는 ENDED가 됩니다. 예약을 PAUSED로 바꾸면 자동 시작하지 않습니다.
- 기간·변형·타기팅·목표·가드레일은 시작 또는 예약 후 고정합니다. 설명·진행 상태 변경과 종료 전 참여 비율 확대는 가능합니다.
- SDK는 설정에 포함된 기간을 매 평가마다 확인합니다. 설정 서버가 중단돼도 알고 있는 종료 시각 이후에는 할당하지 않습니다. 수동 종료·일시중지는 설정 재동기화가 필요합니다.
- 자동 시작은 Admin 가동, 서버 설정 캐시와 SDK 폴링 주기의 영향을 받습니다. 호스트 시계는 동기화해야 합니다. 종료 후 지연 도착한 노출·전환 이벤트는 Phase 1 정책대로 수집합니다.

설정 payload 예시:

```json
{"key":"checkout","status":"ACTIVE","variants":[{"name":"A","weight":50},{"name":"B","weight":50}],
 "trafficAllocation":10,"startsAt":"2026-09-20T00:00:00Z","endsAt":"2026-09-27T00:00:00Z"}
```

## Admin 로그인

필수 환경변수:

- `PRISM_ADMIN_USERNAME`: 관리자 이름 (1–100자).
- `PRISM_ADMIN_PASSWORD_HASH`: BCrypt 해시. 평문 비밀번호를 넣지 않습니다. 예를 들어 `htpasswd -nBC 12 admin`의 결과에서 `admin:`을 제외한 해시를 사용합니다.

선택 환경변수 `PRISM_ADMIN_VIEWER_USERNAME`, `PRISM_ADMIN_VIEWER_PASSWORD_HASH`를 함께 지정하면 조회 전용 계정을 추가할 수 있습니다. 관리자와 다른 이름을 사용합니다. 계정 설정이 없거나 잘못되면 Admin 기동에 실패하며 기본 공용 비밀번호는 없습니다.

`/login`에서 로그인합니다. 관리자는 실험을 변경하고, 조회자는 목록·통계·이력과 기록 없는 시뮬레이션을 볼 수 있습니다. 조회자의 생성·수정·삭제 요청은 서버에서 403으로 차단합니다. 폼 변경·로그아웃에는 CSRF 토큰이 필요합니다. 변경 이력의 `actor`는 인증 계정명이며 자동 전환은 `system:scheduler`입니다. 기존 이력의 actor는 NULL로 유지합니다.

현재 계정은 환경 설정으로 관리하며 변경 후 재시작이 필요합니다. SSO·계정 관리 UI·실험별 세부 권한은 이번 범위에 포함하지 않습니다. 외부 운영에서는 HTTPS와 보안 세션 쿠키(`SERVER_SERVLET_SESSION_COOKIE_SECURE=true`)를 설정하세요. 내장 로그인과 CSRF는 [Spring Security Form Login](https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/passwords/form.html)을 사용합니다.

## API 키와 SDK

API 서버의 `PRISM_API_KEYS`에 32–512자의 충분히 무작위인 키를 지정합니다. `openssl rand -hex 32` 등으로 생성할 수 있습니다. 키를 저장소나 로그에 넣지 않습니다. 쉼표로 여러 키를 설정하면 교체 기간에 이전 키와 새 키를 함께 수락합니다. 변경 후 API 프로세스를 재시작합니다. 키가 없거나 잘못되면 웹 API 기동에 실패합니다.

모든 `/v1/*` 경로는 `X-Prism-Api-Key` 헤더를 요구합니다. 설정·노출 조회·배치 이벤트뿐 아니라 기존 원격 assign/conversions도 보호합니다. 누락·오류 키는 401입니다. 세션·쿼리 파라미터로는 인증하지 않으며 SDK는 리다이렉트를 따라 키를 다른 호스트로 보내지 않습니다. API 키는 서버측 SDK용이며 브라우저 공개 코드에 배포하지 않습니다. 운영 전송에는 HTTPS를 사용합니다.

```kotlin
val client = PrismClient(
    "https://prism.example.com",
    options = PrismClientOptions(apiKey = System.getenv("PRISM_CLIENT_API_KEY"))
)
```

```java
var client = new PrismClient("https://prism.example.com", System.getenv("PRISM_CLIENT_API_KEY"));
```

스타터:

```yaml
prism:
  client:
    url: https://prism.example.com
    api-key: ${PRISM_CLIENT_API_KEY}
```

SDK의 모든 LOCAL·REMOTE HTTP 경로에 키를 적용합니다. SDK 옵션·스타터 속성의 `toString()`에서는 키를 가립니다. 키를 생략하는 기존 SDK 생성자도 남지만 인증을 요구하는 새 API에는 연결되지 않습니다.

## 가드레일과 입력 검증

Admin에서 목표와 별개인 가드레일 이벤트를 줄마다 하나씩 최대 20개 지정합니다. 예를 들어 목표는 `purchase`, 가드레일은 `payment_failed`나 `crash`입니다. 이벤트별 통계 화면은 목표/가드레일/보조를 구분합니다. 가드레일을 목표 CVR 분자에 섞지 않으며, 가드레일 임계치·자동 중단·별도 유의성 판정은 제공하지 않습니다. 가드레일은 시작·예약 전에 정하고 이후 잠급니다.

설명은 255자, 타기팅 규칙은 최대 100개·각 10,000자입니다. 폼의 변형은 최대 256개이며 변형·규칙 인덱스는 0부터 연속되어야 합니다. SpEL은 저장 시 **실행 없이 문법만 검사**합니다. 실행 결과는 사용자 속성에 따라 달라지므로 시뮬레이터는 기간·참여 비율·해시 분할만 확인하고 타기팅은 실제 속성을 넣은 SDK 평가로 확인하세요. 시뮬레이터는 기간 밖·참여 제외를 `미참여`로 표시합니다.

잘못된 입력은 400으로 원래 생성·수정 폼을 표시하며 입력한 값과 오류 메시지를 유지합니다. 중복 키·잘못된 가중치·숫자 형식 오류도 수정 후 같은 폼에서 다시 저장할 수 있습니다. 오류 화면의 사용자 입력은 HTML 이스케이프하며, 저장된 엔티티를 임시 표시 값으로 변경하지 않습니다. Bean Validation 제약과 가중치 클래스 검증을 [Spring MVC의 `@Valid`/`BindingResult` 흐름](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/modelattrib-method-args.html)으로 처리하고, 서비스 계층도 직접 호출에 대비해 검증합니다. 기존 잘못된 실험을 중지·종료하는 경로는 유지합니다.

없는 실험은 오류 페이지와 404, 동시 변경/DB 제약 충돌은 내부 SQL을 노출하지 않는 오류 페이지와 409를 반환합니다. 그 밖의 4xx/5xx에도 기본 Whitelabel 대신 안내 페이지를 제공합니다.

## 배포 순서

1. SDK/스타터 소비자에 새 버전과 API 키를 먼저 배포합니다. 기존 API는 추가 헤더를 무시하므로 준비할 수 있습니다. **구버전 SDK는 참여 비율·기간을 이해하지 못하므로 운영 기능을 활성화하기 전에 모두 갱신해야 합니다.**
2. DB를 백업하고 API/Admin 쓰기를 중지합니다. 기존 설치는 030까지 적용한 뒤 [031_experiment_operations.sql](../prism-infrastructure/src/main/resources/migrations/031_experiment_operations.sql)을 한 번 실행합니다. 신규 설치는 최신 schema.sql을 사용합니다.
3. API 키, Admin 계정·해시를 설정하고 API/Admin을 함께 갱신합니다. 이전 Admin과 혼용하지 않습니다. 기존 실험은 참여율 100%, 기간 없음, 가드레일 없음으로 유지됩니다.
4. 인증된 SDK 설정 수신·이벤트 전송을 확인한 뒤 참여율·기간·가드레일이 있는 새 실험을 생성합니다. 수동 SQL 마이그레이션과 UTC JDBC 옵션은 이전 단계와 같습니다.

## 검증

2026-09-13 리뷰 수정 후 `./gradlew clean build :prism-api:mysqlTest` 통과: 일반 테스트 159개, 실제 MySQL 테스트 4개, 독립 Java 소비자 2종(POM/Gradle metadata). 실패·건너뜀은 없습니다. MySQL 검증은 로컬 MySQL 8.4.11에 전용 임시 데이터베이스를 생성하여 실행했습니다.

- Core: 0/5/40/100% 참여, 참여 확대 시 동일 변형 유지, 참여자 내부 50/50 분할, 시작 포함·종료 제외.
- SDK/API: 인증 없는 요청·잘못된 키 차단과 교체 키 수락, LOCAL/REMOTE 인증, 설정 기간/참여 전파, 장애 중 캐시된 종료 시각 적용.
- Admin HTTP/JPA: 로그인 실패/성공·CSRF·조회자 쓰기 차단·로그아웃, 예약·시작·중지·종료, 반복 전환의 감사 중복 방지, 참여 확대·축소 거부, 가드레일 구분, 검증 오류.
- MySQL: 신규/마이그레이션 × UTC/KST에서 기간 컬럼의 실제 UTC 저장·스케줄 조회·가드레일 대소문자 구분·actor와 기존 로그 보존.
- 독립 Java 소비자: POM/Gradle metadata로 API 키와 새 생성자를 사용해 로컬 평가·기간·참여·이벤트 전송 실행.

## 리뷰에서 보완한 항목

- 생성 화면의 상태 선택이 무시되던 동작을 제거했습니다. 새 실험은 DRAFT만 표시·수락하고 생성 후 시작·예약합니다.
- 잘못된 컬렉션 인덱스의 바인딩 500 오류를 사전 검증으로 차단했습니다. 없는 실험의 잘못된 입력도 404로 처리합니다.
- BCrypt 비용·문자 집합까지 기동 시 검증합니다. 관리자와 조회자 모두 적용합니다.
- 시뮬레이터의 미참여 결과를 표시하고 타기팅·상태 평가 범위를 화면에 안내합니다.
- #26 P1-4의 오류 폼·입력값 유지·Bean Validation·오류 페이지를 완성했습니다. 노출 이력이 있는 DRAFT도 수정 화면에 잠금 상태를 표시합니다.
- 동시 스케줄러의 단일 감사 기록, 놓친 예약의 즉시 종료, 일시중지 예약 유지, SCHEDULED 설정 배포 제외, 중복 API 키 헤더 거부를 회귀 검증합니다.

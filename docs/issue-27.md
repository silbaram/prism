# 이슈 #27 구현 결정과 마이그레이션

[원본 이슈](https://github.com/silbaram/prism/issues/27)의 목적은 중복 API를 줄이고 A/B 전환 지표의 의미를 보장하는 것입니다.

## 확정한 구현

| 항목 | 결정 및 동작 |
|---|---|
| A-1 | `goalEventName` 도입. 목표 이벤트만 집계하고 노출/전환 모두 변형별 고유 사용자 수를 사용합니다. 다중·실패 이벤트는 보조 지표로 유지합니다. |
| A-2 | 노출 없는 전환은 `9100`으로 거부하고 저장하지 않습니다. 저장 포트와 DB의 variant는 non-null입니다. 성공/거부는 서로 다른 결과 타입입니다. |
| A-3 | `winnerVariant`와 승자 UI를 제거합니다. CVR, 표본 수, Wilson 95% 신뢰구간을 표시합니다. |
| B-1 | 메서드 Router와 어노테이션 제거. Strategy를 사용하며 JDK/CGLIB 프록시의 advice가 보존됩니다. |
| B-2 | ThreadLocal 제거. 사용자·실험별 성공한 노출 조회를 TTL/최대 크기로 제한해 캐시합니다. 조회 실패는 저장하지 않습니다. |
| B-3 | 이름 기반 userId 추론 제거. 두 Aspect가 프록시를 고려한 공통 메타데이터 추출기를 사용합니다. `@PrismUserId` 누락/중복/null/공백은 명시적으로 실패합니다. |
| B-4 | 사용자 ID 마스킹은 SDK 한 곳, 가중치 규칙은 core 한 곳으로 통합합니다. 생성뿐 아니라 수정도 검사합니다. Simulator 목록은 ExperimentService를 재사용합니다. 인프라 모듈은 단일 모듈로 평탄화하고 SDK 통신 오류 코드를 SDK로 옮깁니다. |

저장소에는 다른 영속성 구현이나 Redis 모듈이 없어 인프라 부모 모듈을 제거했습니다. 패키지명은 유지하고 Gradle 경로만 `:prism-infrastructure`로 바꿨습니다.

## 이슈 제안에서 보완한 사항

목표 이벤트 필터만 추가해도 같은 사용자의 반복 구매는 CVR을 100%보다 크게 만들 수 있습니다. 따라서 분자·분모를 모두 고유 사용자로 정의합니다. 집계는 변형별이며, 실험 정의 변경으로 사용자가 여러 변형에 노출되면 각 변형에 한 번씩 들어갈 수 있습니다. 그룹 간 독립성이나 승자를 판단하지 않습니다.

전환의 귀속은 조회 시점에 보이는 사용자·실험 노출 중 **DB ID가 가장 큰 노출**입니다. API 서버 시계 대신 DB의 ID 발급 순서를 사용합니다. 동시에 처리된 요청의 커밋 순서까지 보장하는 정의는 아닙니다. 새 전환은 검증한 노출의 `impression_id`를 필수로 저장하며, 집계는 해당 ID와 사용자·실험·변형 일치를 확인합니다. 따라서 노출 서버의 시계가 앞서 있어도 수락한 전환이 집계에서 빠지지 않습니다. `EXISTS`로 반복 노출에 의한 이벤트 수 증폭을 방지합니다. 전체 기간 집계이며 별도의 전환 유효기간은 두지 않았습니다.

기존 전환 행의 `impression_id`는 null로 유지합니다. 기존 로그만으로 서버 간 시계 차이나 실제 인과관계를 복원할 수 없어 ID를 임의로 채우지 않습니다. 이 행들만 기존의 같은 사용자·실험·변형에 대한 `노출 timestamp <= 전환 timestamp` 조건을 유지합니다. 과거 시계 오차로 누락된 지표는 이 변경으로 소급 복원되지 않습니다.

`assign()` 재호출은 읽기 전용 확인이 아닙니다. 전환 시점에 새로운 노출을 만들면 구매한 사용자만 소급 편입될 수 있으므로, `GET /v1/assignments`를 추가해 **기존 노출만 조회**합니다. Aspect/Tracker는 이 조회와 성공 캐시를 사용합니다. 명시적 `assign()`은 항상 서버를 호출하며 노출 저장이 커밋된 뒤 응답합니다. 비동기 저장과 즉시 전환 사이의 경쟁 조건을 없앴습니다.

캐시 미스의 동시 조회는 Caffeine으로 합칩니다. 캐시의 기본 TTL은 기록 후 30초, 최대 크기는 10,000이며 프로세스 단위입니다. 전환의 최종 수락과 귀속은 항상 서버가 결정합니다. 중지/종료한 실험의 과거 노출에 대한 후속 전환은 수락할 수 있습니다.

SDK는 HTTP 상태만 보고 성공을 추정하지 않습니다. 전환 응답의 `resultCode`를 확인하고 서버 거부·통신 오류에는 `false`를 반환합니다. 서버가 정책을 강제하므로 소스 텍스트를 검색해 빌드를 실패시키던 Gradle 태스크를 제거했습니다.

## 지표/UI 정책

- 새 실험에는 공백이 아닌 1–255자의 목표 이벤트가 필요합니다. 생성/수정 폼에서 설정합니다.
- 기존 실험의 목표를 `purchase` 또는 `success`로 임의 추정하지 않습니다. DB 값은 null로 두며 CVR·전환 사용자·신뢰구간은 `—`로 표시합니다.
- 목표가 있어도 노출 0명인 변형은 CVR과 신뢰구간을 `—`로 표시합니다.
- 사용자 ID, 실험 키, 변형 이름, 목표/전환 이벤트 이름은 대소문자·악센트·후행 공백을 구분합니다. MySQL 8.0.17+의 `utf8mb4_0900_bin`을 각 식별자 컬럼에 명시해 SDK의 문자열 비교와 일치시킵니다. Admin 목표 이벤트 입력은 앞뒤 공백을 제거한 뒤 저장합니다. 보조 이벤트는 `/admin/experiments/{id}/events`에 사용자 수와 발생 횟수를 표시합니다. 이 화면은 순서를 추론하는 퍼널 분석이 아닙니다.
- 목표를 수정하면 전체 과거 기간의 CVR이 새 이벤트로 재계산됩니다. 실행 중 목표나 가중치를 변경하면 기존 실험과 다른 해석이 필요하므로 새로운 실험 키를 사용하는 편이 명확합니다.
- 신뢰구간은 각 변형의 관측 비율에 대한 서술적 구간입니다. 그룹 간 검정, 반복 관측 보정, 다중 비교 보정 또는 승자 판정은 제공하지 않습니다.

## 배포 순서

1. DB 백업 후 API/Admin 쓰기를 중지합니다. 먼저 새 코드만 배포하면 Hibernate 스키마 검증에 실패합니다.
2. 기존 설치는 [027_metric_integrity.sql](../prism-infrastructure/src/main/resources/migrations/027_metric_integrity.sql), [028_exact_identity_and_attribution.sql](../prism-infrastructure/src/main/resources/migrations/028_exact_identity_and_attribution.sql)을 **순서대로 각각 한 번** 적용합니다. 이미 027을 적용했다면 028만 실행합니다. MySQL 8.0.17+가 필요하며 DDL은 암묵적으로 커밋되므로 전체 트랜잭션 롤백을 가정하지 마세요.
3. 027은 `variant=null` 행을 `log_conversion_unattributed_archive`에 보관한 뒤 운영 로그에서 제거하고 NOT NULL 제약을 적용합니다. 아카이브는 자동 삭제하지 않습니다. 건수와 백업을 확인한 뒤 별도 보존 정책으로 관리하세요.
4. 028은 식별자 컬럼의 비교 규칙, 최신 노출 조회 인덱스를 변경하고 nullable `impression_id`와 외래키를 추가합니다. 기존 로그의 문자열은 변경하지 않습니다. 이전에 대소문자 등의 차이가 합쳐졌던 집계 값은 달라질 수 있습니다. 외래키가 참조하는 노출 로그를 정리할 때는 전환 로그의 보존 정책도 함께 적용해야 합니다.
5. 새 API/Admin을 배포합니다. 각 기존 실험의 실제 목표 이벤트를 Admin에서 지정합니다.
6. SDK/스타터 소비자의 제거 API를 아래와 같이 바꾸고 다시 컴파일해 배포합니다. 캐시 미스 조회를 쓰는 새 SDK는 새 API가 필요합니다.

신규 DB에는 갱신한 [schema.sql](../prism-infrastructure/src/main/resources/schema.sql)을 사용합니다. Docker Compose의 초기화 스크립트 경로도 변경됐습니다. 기존 Docker 볼륨에는 초기화 SQL이 다시 실행되지 않으므로 위 마이그레이션이 필요합니다.

롤백은 쓰기 중지 상태에서 배포 전 백업 복원과 이전 애플리케이션 배포를 함께 수행하세요. 마이그레이션을 중간부터 무작정 재실행하거나 아카이브를 제거하지 마세요.

## 소비자 코드 변경

| 이전 코드/의존성 | 변경 |
|---|---|
| `PrismContext.getCurrentVariant()` | 노출 시점의 `PrismExperimentClient.assign(...).variant`를 명시적으로 전달하거나 Strategy 사용 |
| `PrismContext.wasActuallyAssigned()` | `AssignmentOutcome.assigned` 또는 `trackIfAssigned` |
| `@PrismVariantMethod`, `PrismVariantMethodRouter.route(...)` | 타입이 있는 인터페이스 + `@PrismStrategy` + `PrismStrategyResolver.resolve(...)` |
| `userIdParam = "customerId"` | 파라미터에 `@PrismUserId` 추가 |
| `PrismConversionTracker(prismClient)` | `PrismConversionTracker(prismExperimentClient)` |
| `PrismTrackConversionAspect(prismClient)` | `PrismTrackConversionAspect(prismExperimentClient)` |
| `ResponseCode.GENERAL_ERROR` | `SdkResponseCode.CLIENT_ERROR` (`9999` 유지) |
| `PrismClient.trackConversion(...): Unit` | 반환값 `Boolean`으로 변경. 바이너리 소비자 재빌드 필요 |
| `:prism-infrastructure:persistence-jpa` / `persistence-jpa` 아티팩트 | `:prism-infrastructure` / `prism-infrastructure` |
| DTO의 `winnerVariant` | 제거. `goalEventName`, `cvr`, `confidenceInterval` 표시 |

`@PrismExperiment`는 이제 노출 기록만 담당합니다. 변형별 업무 로직은 명시적 클라이언트나 Strategy에서 처리합니다. 전환 추적은 다른 요청/스레드에서도 가능하지만 `suspend` 함수나 Future의 완료 시점을 AOP가 감지하는 기능은 추가하지 않았습니다. 완료 후 명시적인 추적 호출을 사용하세요.

`@PrismUserId`는 구현 메서드, 인터페이스, 상위 클래스의 대응 메서드 파라미터에서 찾습니다. 제네릭 브리지 메서드도 실제 구현에 연결하며 다른 오버로드는 제외합니다. 동일 인덱스의 중복 선언은 허용하고, 계층 사이에서 다른 인덱스를 지정하면 업무 메서드 실행 전에 실패합니다. JDK/CGLIB 프록시에 같은 규칙을 적용합니다. Aspect를 실행하는 메서드 어노테이션(`@PrismExperiment`, `@PrismTrackConversion`)은 구현 메서드에 둡니다.

Admin 입력 오류는 `400 text/plain;charset=UTF-8` 및 `X-Content-Type-Options: nosniff`로 반환합니다. 브라우저가 HTML을 요청해도 실험 키에 포함된 태그는 일반 텍스트로 표시됩니다.

## 빌드 복구

누락돼 있던 API outbound 포트와 JPA 어댑터를 복원했습니다. 원인이 되는 광범위한 `out/` ignore를 루트 출력 디렉터리에 한정했습니다. 공통 라이브러리 모듈에도 Spring Boot BOM을 적용하고, Boot 3.5 스타터 혼용을 제거해 기존 루트 버전인 Boot 4.0.0에 맞췄습니다. Kotlin kapt 플러그인 버전과 Gradle 9용 테스트 런처도 명시합니다.

모든 라이브러리의 Maven publication에 `versionMapping`을 적용합니다. API·runtime 의존성 모두 `runtimeClasspath`에서 해석된 버전을 게시하므로 POM뿐 아니라 Gradle `.module`에도 Jackson·SLF4J·Caffeine 등의 버전이 포함됩니다. 별도 Spring Boot BOM을 가져오지 않는 SDK 소비자도 의존성을 해석할 수 있습니다.

## 검증

2026-09-09, JDK 21 환경에서 `./gradlew build --offline --no-daemon --max-workers=2` 성공. 일반 테스트 88개와 별도 `:prism-api:mysqlTest`의 MySQL 8.4.11 테스트 2개, 총 90개가 통과했으며 실패·건너뜀은 없습니다. API/Admin 실행 JAR과 라이브러리 JAR도 생성했습니다.

`./gradlew test`는 HTTP→컨트롤러→서비스→JPA→H2 통합, 이벤트 필터/사용자 중복/선행 노출 쿼리, Wilson 구간, 생성/수정 검증, SDK 거부 응답·캐시·동시성, AOP 순서·반복 어노테이션·프록시 advice를 검사합니다. 리뷰 회귀 테스트는 HTML 요청의 안전한 입력 오류 응답, 인터페이스/상위 클래스/제네릭 파라미터 추출, 프록시 메타데이터 충돌과 캐시 격리, 노출 ID를 통한 시계 역전 처리도 확인합니다.

`./gradlew :prism-sdk:verifySdkPublication`은 별도의 소비자 검증 2건을 실행하며 SDK의 `check`/`build`에 포함됩니다. SDK와 common을 빌드 디렉터리의 임시 Maven 저장소에 게시한 뒤, 의존성 관리 플러그인이나 BOM이 없는 독립 Java 프로젝트가 Gradle `.module`과 POM을 각각 사용해 컴파일·실행합니다. `.module` 검증은 POM으로 폴백하지 않으며, 실제 할당 응답 처리·캐시·전환 요청까지 검사합니다. 일반 모듈 간 프로젝트 의존성 테스트에서 놓치는 배포 메타데이터 누락을 확인하기 위한 검증입니다.

MySQL 전용 검증은 별도 태스크로 실행합니다. 일반 `test`에는 포함하지 않습니다. 테스트 전용 서버의 URL과 CREATE/DROP DATABASE 권한이 있는 계정을 지정하세요. 각 실행은 임의 이름의 DB를 만들고 자신의 DB만 제거합니다.

```bash
PRISM_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/' \
PRISM_TEST_MYSQL_USERNAME=root PRISM_TEST_MYSQL_PASSWORD=root \
./gradlew :prism-api:mysqlTest --no-daemon --max-workers=2
```

두 경로(신규 `schema.sql`, 과거 스키마 → 027 → 028)를 실제 MySQL에서 검사합니다. 기본 DB 정렬 규칙을 기존 `utf8mb4_unicode_ci`로 유지한 채 컬럼별 비교 규칙, 사용자별 노출 거부와 CVR 분모, 실험 키/변형/이벤트 분리, 시계 역전 시 귀속, 기존 로그 보존과 아카이브를 확인합니다. 운영 DB에는 자동 적용하지 않습니다.

## 설계 참고

- [NIST Wilson 신뢰구간 식](https://www.itl.nist.gov/div898/handbook/prc/section2/prc241.htm)
- [Caffeine 원자적 캐시 로딩](https://github.com/ben-manes/caffeine/wiki/Population)
- [Caffeine 크기/시간 제한](https://github.com/ben-manes/caffeine/wiki/Eviction)
- [Spring Boot BOM 적용](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)
- [MySQL 이진 정렬 규칙과 후행 공백 비교](https://dev.mysql.com/doc/refman/8.4/en/charset-binary-collations.html)
- [Gradle 해석된 의존성 버전 게시](https://docs.gradle.org/current/userguide/publishing_maven.html#publishing_maven:resolved_dependencies)

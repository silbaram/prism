# 프레임워크 독립 SDK와 타기팅 규칙

## 적용 범위

`prism-core`, `prism-common`, 기본 `prism-sdk`는 Spring 실행 라이브러리와 Spring Boot BOM을 전이 의존하지 않는다. JDK 21 이상의 일반 Java/Kotlin 서비스에서 사용할 수 있다. SDK의 JSON·캐시·로깅 의존성은 유지한다.

Spring 스타터는 자동 설정·전략 Bean 탐색·어노테이션·트랜잭션 연동을 제공한다. API·Admin·JPA 구현은 Spring 기반이다. 이번 분리는 도입 서비스에 필요한 SDK와 코어의 의존성을 정리한 것이며, 서버 프레임워크 변경·JDK 17 지원·JavaScript/Python SDK 추가를 포함하지 않는다.

## 일반 JVM 서비스

```kotlin
implementation("io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT")
```

```kotlin
val client = PrismClient("https://prism.example.com", apiKey = requireNotNull(System.getenv("PRISM_CLIENT_API_KEY")))
val experiments = PrismExperimentClient(client)
val outcome = experiments.assign("user-123", "checkout", mapOf("age" to 25, "country" to "KR"))
// outcome에 맞는 경험을 제공한다. 업무 트랜잭션은 성공적으로 커밋한 뒤 전환을 기록한다.
experiments.track(outcome, "purchase")
// 같은 client를 공유하고 애플리케이션 종료 시 호출한다.
client.close()
```

전환이 업무 트랜잭션의 성공을 뜻한다면 호출자가 커밋 완료를 확인한 후 기록해야 한다. 기본 SDK는 외부 프레임워크의 트랜잭션 상태를 추정하지 않는다. Spring 스타터의 커밋 후 어노테이션 계약은 유지한다.

## Spring 없는 타기팅

기본 SDK는 `prism:v1:`로 시작하는 JSON 데이터 규칙을 지원한다. 관리자 화면의 타기팅 조건에 아래 문자열을 입력할 수 있다. 기존 조건을 자동 변환하거나 재해석하지 않는다.

```text
prism:v1:{"all":[{"attribute":"age","op":"gte","value":20},{"attribute":"country","op":"eq","value":"KR"}]}
```

| 형태 | 의미 | 예시 |
|---|---|---|
| `eq`, `ne` | 같은 타입의 값 비교 | `{"attribute":"premium","op":"eq","value":true}` |
| `gt`, `gte`, `lt`, `lte` | 숫자 대소 비교 | `{"attribute":"age","op":"gte","value":20}` |
| `in` | 스칼라 목록 중 일치하는 값 | `{"attribute":"country","op":"in","value":["KR","JP"]}` |
| `exists` | 값이 null이 아닌 속성 존재 | `{"attribute":"country","op":"exists"}` |
| `all`, `any` | 하위 조건의 AND·OR | `{"all":[조건1,조건2]}` |
| `not` | 하위 조건 결과 반전 | `{"not":조건}` |

- 규칙의 리터럴은 문자열·불리언·숫자다. JVM 숫자 속성은 Byte·Short·Int·Long·Float·Double·BigDecimal·BigInteger를 지원한다. 숫자는 정밀도를 유지해 비교하며 문자열을 숫자나 불리언으로 자동 변환하지 않는다.
- 속성 이름은 Map의 정확한 키다. `device.country`는 중첩 객체 접근이 아닌 같은 이름의 키다.
- 누락·타입 불일치·NaN·Infinity는 비교 조건을 만족하지 않는다. `not`은 그 false 결과도 반전하므로 누락을 제외해야 하면 `exists`와 함께 사용한다.
- 조건별 필드는 정해진 형태만 허용한다. 중복 JSON 키, 후행 데이터, 임의 필드·연산자·메서드 호출은 거부한다.
- 길이는 최대 10,000자, 조건 깊이는 16, 조건 노드는 256개다. `all`·`any`와 `in` 목록은 1–100개를 지원한다. 숫자의 정밀도는 256자리 이하, 스케일은 -1024–1024 범위다.
- 여러 타기팅 규칙은 기존과 같이 AND로 결합한다. 빈 문자열 조건은 모든 사용자를 대상으로 한다.
- 새 버전을 알지 못하는 평가기는 규칙을 무시하거나 SpEL로 실행하지 않고 거부한다.

## 기존 SpEL 규칙

`age >= 20 && country == 'KR'` 같은 기존 규칙은 `prism-targeting-spel`이 처리한다. Spring 스타터와 API/Admin에는 이 호환 모듈이 포함되므로 기존 평가 방식이 유지된다. 일반 SDK 소비자는 필요한 경우 명시적으로 추가한다.

```kotlin
implementation("io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT")
runtimeOnly("io.github.silbaram.prism:prism-targeting-spel:0.0.1-SNAPSHOT")
```

JVM ServiceLoader가 호환 평가기를 찾는다. 호환 모듈은 Spring Expression 라이브러리를 가져오지만 Spring 애플리케이션 컨텍스트를 요구하지 않는다. 기존 SpEL의 타입·메서드 접근 의미도 유지하므로 신뢰하는 관리자가 작성한 규칙에 사용한다. Spring 의존성 없이 운영하려면 새 데이터 규칙 또는 직접 제공한 평가기를 사용한다.

## 평가기 확장

코어의 `TargetingEvaluator` 인터페이스는 규칙 문법 검증과 사용자별 평가를 분리한다. 일반 SDK에는 `PrismClientOptions(targetingEvaluator = evaluator)`로 전달하고, Spring 스타터에는 같은 타입의 Bean을 제공한다. 기본 Spring Bean은 사용자 Bean이 있으면 생성하지 않는다.

평가기는 스레드 안전해야 한다. 문법 검증은 사용자 데이터를 실행하지 않으며 지원하지 않는 문법은 `IllegalArgumentException`으로 거부해야 한다. LOCAL 평가에 적용되며 REMOTE 요청의 서버 측 평가기를 교체하는 설정은 아니다. 서버의 저장·검증 방식과 다른 언어 SDK를 함께 사용한다면 동일한 규칙 계약을 별도로 확보해야 한다.

## 거절 진단과 설정 갱신

설정 동기화 시 규칙 문법과 평가기 지원 여부를 확인한다. 거부된 규칙이 있는 실험은 배정·신규 노출·전환 등록을 차단한다. 배정 응답 코드는 `9997`이며, 과거 노출 참조를 이용한 전환도 이 상태에서는 false를 반환한다. `client.targetingConfigurationErrors`에서 실험 키와 오류 종류를 확인할 수 있다. 로그·진단에는 규칙 원문과 사용자 속성을 넣지 않는다.

한 실험의 규칙을 지원하지 못해도 다른 실험의 정상 설정과 일시중지·종료 변경은 설치한다. 전체 설정을 버리고 이전 활성 실험을 계속 사용하는 방식으로 처리하지 않는다. 정상적인 대상 제외는 기존 배정 결과로 반환하며 `9997`과 구분한다.

## 업그레이드와 검증

1. 새 규칙 형식을 이해하는 API/Admin을 먼저 배포한다.
2. SDK/스타터 소비자를 재컴파일한다. 기존 일반 SDK 소비자 중 SpEL 규칙을 사용하는 구성은 호환 모듈을 추가한다.
3. 소비자들이 지원하는 규칙 형식을 확인한 뒤 새 실험에서 데이터 규칙을 사용한다. 실행 중인 기존 실험의 대상 규칙을 바꾸어 마이그레이션하지 않는다.

DB의 타기팅 문자열과 설정 API 형식은 유지하므로 이번 변경을 위한 DB 마이그레이션은 없다.

```bash
./gradlew test :prism-sdk:verifySdkPublication :prism-api:bootJar :prism-admin:bootJar
```

게시 검증은 독립 Java 프로젝트에서 기본 SDK와 SpEL 호환 구성을 각각 Gradle 모듈 메타데이터·Maven POM으로 소비한다. 기본 구성의 런타임 의존성에 Spring 구성 요소가 없는지 검사하고, 양쪽 구성에서 배정·타기팅·노출·전환·배치·분석 메타데이터·홀드아웃·파일 배정 유지 흐름을 실행한다. 실제 MySQL·Kafka와 운영 부하 검증은 별도다.

### 이번 구현의 검증 결과

2026-10-10, `refactor/framework-independent-sdk`의 로컬 변경 기준이다.

| 검증 | 결과 |
|---|---|
| 기본 회귀 테스트 | 342개 통과, 실패·오류·건너뜀 0건 |
| 기본 SDK 게시 소비자 | Gradle 메타데이터·Maven POM 모두 통과. Spring 런타임 구성 요소 없음 |
| SpEL 호환 게시 소비자 | 두 메타데이터 형식 모두 기존 규칙 평가·측정 흐름 통과 |
| API/Admin 실행 JAR | `bootJar` 빌드 통과 |
| 추가 리뷰 | 거절된 규칙의 과거 노출 참조로 전환을 등록하는 경로를 재현하고 차단 |
| 실제 MySQL·Kafka·운영 부하 | 이번 검증에 포함하지 않음 |

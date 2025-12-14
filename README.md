# Prism (A/B Testing System)

Prism은 확장 가능한 A/B 테스트 플랫폼입니다.
무상태 분배 엔진, 실험 관리 Admin, 고성능 Traffic Serving API로 구성되어 있습니다.

## 1. 프로젝트 구조
- **prism-core**: 핵심 도메인 로직 (MurmurHash, TrafficSplitter, Targeting). 순수 Kotlin 라이브러리.
- **prism-common**: 모듈 간 공유되는 데이터 모델 및 공통 상수 (DTO, Enums).
- **prism-sdk**: 클라이언트 애플리케이션 연동을 위한 Java/Kotlin 클라이언트 라이브러리.
- **prism-spring-boot-starter**: Spring Boot 애플리케이션에서 SDK를 쉽게 설정하고 사용할 수 있도록 지원하는 스타터.
- **prism-admin**: 실험 관리 및 통계 분석 서버 (Spring Boot, JPA).
- **prism-api**: 트래픽 분배 및 로그 수집 서버 (Spring Boot, Caffeine, Async).
- **prism-infrastructure**: 데이터베이스 엔티티 및 공통 인프라 설정 (JPA Entities, Schema).

## 2. 시작하기 (Getting Started)

### 필수 요구사항
- JDK 17 이상
- Docker & Docker Compose (로컬 DB 실행용)
- Gradle (또는 IntelliJ IDEA 사용 권장)

### 로컬 개발 환경 설정 (Local Setup)
**1. 데이터베이스 실행**
프로젝트 루트의 `docker` 디렉토리에서 Docker Compose를 실행하여 MySQL 데이터베이스를 준비합니다.
```bash
cd docker
docker-compose up -d
```
이 명령어는 MySQL 컨테이너를 실행하고, `prism-infrastructure` 모듈의 `schema.sql`을 사용하여 테이블을 자동으로 생성합니다.

**2. 애플리케이션 실행**
데이터베이스가 준비되면 애플리케이션을 실행할 수 있습니다.

### 빌드 및 테스트
이 프로젝트는 Gradle을 사용합니다. 터미널에 `gradle`이 설치되어 있지 않다면, **IntelliJ IDEA**로 프로젝트를 열어 실행하는 것을 권장합니다.

```bash
# 전체 테스트 실행
./gradlew test

# 모듈별 테스트 실행
./gradlew :prism-core:test
./gradlew :prism-common:test
./gradlew :prism-api:test
./gradlew :prism-admin:test
./gradlew :prism-sdk:test
```

### 애플리케이션 실행
**Admin 서버 실행** (포트 8080)
```bash
./gradlew :prism-admin:bootRun
```

**API 서버 실행** (포트 8081 - 설정 필요)
`prism-api`는 기본적으로 8080을 사용하므로, 동시에 띄우려면 `application.yml`에서 포트를 변경해야 합니다.

## 3. 주요 기능
- **실험 생성**: Admin API를 통해 실험(Variants, 타겟팅 규칙)을 생성합니다.
- **트래픽 분배**: API 서버(`GET /v1/assign`)를 통해 사용자를 그룹에 할당합니다.
- **로그 수집**: 할당 및 전환 로그가 비동기로 DB에 저장됩니다.
  - 할당 로그: `GET /v1/assign` 호출 시 자동 기록
  - 전환 로그: `POST /v1/conversions` 호출 시 기록
- **통계 분석**: Admin API를 통해 실험별 CVR(전환율)과 승자를 확인합니다.

## 4. 클라이언트 연동 (Client Integration)
Prism은 Java/Kotlin 애플리케이션을 위한 공식 SDK를 제공합니다.

### SDK 설계 원칙: 안전 우선 (Fail-safe)

Prism SDK는 **"A/B 테스트 실패가 메인 비즈니스 로직을 중단시키지 않는다"**는 원칙으로 설계되었습니다.

#### 핵심 동작 방식

**1. `assign()` - Variant 할당 (안전한 응답)**
- API 호출 실패 시 **예외를 던지지 않고** 에러 응답을 반환합니다
- `variant`가 `null`이면 실패한 것으로 판단하고 기본값을 사용합니다
- 모든 에러는 로그에 기록됩니다

```kotlin
// Kotlin
val response = prismClient.assign("user-123", "exp-1")
val variant = response.variant ?: "control"  // null이면 기본값 사용

// Java
AssignmentResponse response = client.assign("user-123", "exp-1");
String variant = response.getVariant() != null ? response.getVariant() : "control";
```

**2. `trackConversion()` - 전환 추적 (조용히 실패)**
- 실패 시 로그만 기록하고 예외를 던지지 않습니다 (fire-and-forget)
- 메인 비즈니스 로직에 영향을 주지 않습니다

```kotlin
// Kotlin
prismClient.trackConversion("user-123", "exp-1", "purchase")
// 실패해도 앱이 중단되지 않음

// Java
client.trackConversion("user-123", "exp-1", "purchase");
```

#### 안전 우선 방식의 장점
- ✅ API 서버 다운 시에도 앱이 정상 동작
- ✅ 네트워크 오류로 앱이 크래시 나지 않음
- ✅ A/B 테스트 실패 시 자동으로 기본값(control) 사용
- ✅ 간단한 사용법 (try-catch 불필요)
- ✅ Java/Kotlin 모두 자연스럽게 사용 가능

### Gradle 설정
`prism-spring-boot-starter`를 사용하면 별도의 설정 없이 Spring 환경에서 쉽게 사용할 수 있습니다.

```kotlin
implementation("io.github.silbaram.prism:prism-spring-boot-starter:0.0.1-SNAPSHOT")
```

더 자세한 사용법은 [prism-spring-boot-starter README](prism-spring-boot-starter/README.md) 또는 [prism-sdk README](prism-sdk/README.md)를 참고하세요.

### SDK 직접 사용 (Pure SDK Usage)
Spring Boot가 아닌 환경이나 더 세밀한 제어가 필요한 경우 `PrismClient`를 직접 사용할 수 있습니다.

#### 1. 의존성 추가
```kotlin
dependencies {
    implementation("io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT")
}
```

#### 2. PrismClient 생성 및 사용
```kotlin
import io.github.silbaram.prism.sdk.PrismClient
import java.time.Duration

// 클라이언트 생성
val client = PrismClient(
    baseUrl = "http://localhost:8081",
    timeout = Duration.ofSeconds(5)
)

// Variant 할당 (안전한 방식)
val response = client.assign("user-123", "discount_experiment")
val variant = response.variant ?: "control"  // null이면 기본값 사용

// 비즈니스 로직 적용
when (variant) {
    "A" -> applyDiscount10Percent()
    "B" -> applyDiscount20Percent()
    else -> noDiscount()
}

// 전환 추적 (구매 완료 시)
client.trackConversion("user-123", "discount_experiment", "purchase")
```

#### 3. Java에서 사용
```java
import io.github.silbaram.prism.sdk.PrismClient;
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse;
import java.time.Duration;

// 클라이언트 생성
PrismClient client = new PrismClient(
    "http://localhost:8081",
    Duration.ofSeconds(5)
);

// Variant 할당
AssignmentResponse response = client.assign("user-123", "discount_experiment");
String variant = response.getVariant() != null ? response.getVariant() : "control";

// 비즈니스 로직 적용
if ("A".equals(variant)) {
    applyDiscount10Percent();
} else if ("B".equals(variant)) {
    applyDiscount20Percent();
} else {
    noDiscount();
}

// 전환 추적
client.trackConversion("user-123", "discount_experiment", "purchase");
```

### 어노테이션 기반 사용법 (Annotation-based Usage)
Spring Boot 환경에서는 `@PrismExperiment` 어노테이션을 사용하여 A/B 테스트를 간편하게 적용할 수 있습니다.

#### 1. 의존성 추가
```kotlin
repositories {
    mavenLocal()  // 로컬에 배포한 경우
    mavenCentral()
}

dependencies {
    implementation("io.github.silbaram.prism:prism-spring-boot-starter:0.0.1-SNAPSHOT")
}
```

#### 2. 설정 파일 작성 (application.yml)
```yaml
prism:
  api:
    base-url: http://localhost:8081  # Prism API 서버 주소
```

#### 3. 서비스에서 어노테이션 사용
```kotlin
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.github.silbaram.prism.starter.aop.PrismContext
import org.springframework.stereotype.Service

@Service
class ProductService {

    @PrismExperiment(
        experimentKey = "discount_ab_test",
        defaultVariant = "A"
    )
    fun calculateDiscount(@PrismUserId userId: String, amount: Int): Int {
        // PrismContext에서 할당된 variant 조회
        val variant = PrismContext.getCurrentVariant()

        return when (variant) {
            "A" -> (amount * 0.9).toInt()  // 10% 할인
            "B" -> (amount * 0.8).toInt()  // 20% 할인
            else -> amount                  // 할인 없음
        }
    }
}
```

#### 4. 어노테이션 설명

**@PrismExperiment**
- `experimentKey`: Prism 서버에 등록된 실험의 고유 키
- `defaultVariant`: API 호출 실패 시 사용할 기본 variant
- `userIdParam`: userId를 추출할 파라미터 이름 (기본값: "userId")

**@PrismUserId**
- 메소드 파라미터에 붙여서 어떤 파라미터가 userId인지 명시합니다
- 파라미터 이름이 "userId"가 아닌 경우 필수입니다

**PrismContext.getCurrentVariant()**
- 현재 할당된 variant를 가져옵니다
- `@PrismExperiment`가 붙은 메소드 내부에서만 사용 가능합니다

#### 5. userId 파라미터 전달 방법

**방법 1: @PrismUserId 어노테이션 사용 (권장)**
```kotlin
@PrismExperiment(experimentKey = "my_experiment", defaultVariant = "A")
fun doSomething(@PrismUserId customId: String, otherParam: Int) {
    // ...
}
```

**방법 2: 파라미터 이름으로 지정**
```kotlin
@PrismExperiment(
    experimentKey = "my_experiment",
    defaultVariant = "A",
    userIdParam = "customId"  // 파라미터 이름 명시
)
fun doSomething(customId: String, otherParam: Int) {
    // ...
}
```

**방법 3: 기본 파라미터 이름 사용**
```kotlin
@PrismExperiment(experimentKey = "my_experiment", defaultVariant = "A")
fun doSomething(userId: String, otherParam: Int) {
    // 파라미터 이름이 "userId"면 자동으로 인식
}
```

#### 6. 실제 사용 예제 (Controller)
```kotlin
@RestController
@RequestMapping("/api/products")
class ProductController(
    private val productService: ProductService
) {

    @GetMapping("/{productId}/discount")
    fun getDiscount(
        @PathVariable productId: String,
        @RequestParam userId: String
    ): DiscountResponse {
        val originalPrice = 10000
        val discountedPrice = productService.calculateDiscount(userId, originalPrice)

        return DiscountResponse(
            originalPrice = originalPrice,
            discountedPrice = discountedPrice,
            discountRate = ((originalPrice - discountedPrice) * 100 / originalPrice)
        )
    }
}
```

## 5. 타겟팅 규칙 (SpEL)
`prism-core`는 Spring Expression Language (SpEL)를 지원합니다.
예: `age >= 20`, `os == 'iOS'`, `appVersion > '1.5'`

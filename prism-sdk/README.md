# Prism SDK

Java/Kotlin 애플리케이션에서 Prism A/B 테스트를 사용할 수 있게 해주는 경량 클라이언트 라이브러리입니다.

## 목차
- [이 SDK가 하는 일](#이-sdk가-하는-일)
- [Spring Boot Starter와의 차이](#spring-boot-starter와의-차이)
- [빠른 시작](#빠른-시작)
- [핵심 개념](#핵심-개념)
- [기본 사용법](#기본-사용법)
- [안전한 전환 추적](#안전한-전환-추적)
- [실제 사용 예제](#실제-사용-예제)
- [자주 묻는 질문](#자주-묻는-질문)

---

## 이 SDK가 하는 일

Prism SDK는 두 가지 핵심 기능을 제공합니다:

### 1. 사용자를 그룹에 할당 (Assignment)
Prism API 서버에 요청하여 사용자를 A/B 테스트 그룹(variant)으로 분배합니다.

```
userId: "user-123" + experimentKey: "discount_test"
           ↓
      Prism API 호출
           ↓
      variant: "A" 반환
```

### 2. 전환 이벤트 기록 (Conversion Tracking)
사용자가 목표를 달성했을 때 Prism API 서버에 기록합니다.

```
"user-123"이 "purchase" 이벤트 달성
           ↓
      Prism API에 기록
           ↓
      통계 업데이트
```

### 중요한 특징: Fail-Safe 설계

이 SDK는 **절대 비즈니스 로직을 방해하지 않습니다**.

```kotlin
// ✅ 네트워크 오류가 나도 예외를 던지지 않음
val response = prismClient.assign("user-123", "experiment")
val variant = response.variant ?: "control"  // null이면 기본값 사용

// 비즈니스 로직은 정상 진행
when (variant) {
    "A" -> applyDiscountA()
    "B" -> applyDiscountB()
    else -> noDiscount()  // 오류 시 여기로
}
```

**Fail-Safe 동작**:
- ✅ API 서버 다운 → `variant = null` 반환, 예외 없음
- ✅ 네트워크 타임아웃 → `variant = null` 반환, 예외 없음
- ✅ HTTP 4xx/5xx 에러 → `variant = null` 반환, 예외 없음
- ✅ 로그에만 오류 기록, 비즈니스 로직 계속 실행

---

## Spring Boot Starter와의 차이

Prism을 사용하는 두 가지 방법이 있습니다:

### Prism SDK (이 라이브러리)
- ✅ 순수 Java/Kotlin 라이브러리
- ✅ Spring 없이 사용 가능
- ✅ 직접 API 호출 제어
- ✅ 가볍고 의존성 적음
- 📦 사용 대상: 일반 Java/Kotlin 앱, Android, CLI 도구 등

```kotlin
// 직접 클라이언트 생성
val client = PrismClient("http://api.prism.com")
val response = client.assign("user-123", "experiment")
```

### Prism Spring Boot Starter
- ✅ Spring Boot 자동 설정
- ✅ 어노테이션 기반 사용 (`@PrismExperiment`)
- ✅ AOP로 자동 할당
- ✅ Spring Bean 자동 등록
- 📦 사용 대상: Spring Boot 애플리케이션

```kotlin
// 어노테이션으로 자동 처리
@PrismExperiment(experimentKey = "discount_test")
fun checkout(@PrismUserId userId: String) {
    val variant = PrismContext.getCurrentVariant() ?: "control"
}
```

**어떤 것을 선택할까?**

| 상황 | 추천 |
|------|------|
| Spring Boot 사용 중 | **Spring Boot Starter** |
| 일반 Java/Kotlin 앱 | **Prism SDK** |
| Android 앱 | **Prism SDK** |
| 더 많은 제어 필요 | **Prism SDK** |
| 간편함 우선 | **Spring Boot Starter** |

---

## 빠른 시작

### 1. 의존성 추가

**Gradle (Kotlin DSL)**:
```kotlin
dependencies {
    implementation("io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT")
}
```

**Gradle (Groovy)**:
```groovy
dependencies {
    implementation 'io.github.silbaram.prism:prism-sdk:0.0.1-SNAPSHOT'
}
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.silbaram.prism</groupId>
    <artifactId>prism-sdk</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 2. 클라이언트 생성

**Kotlin**:
```kotlin
import io.github.silbaram.prism.sdk.PrismClient
import java.time.Duration

val prismClient = PrismClient(
    baseUrl = "http://localhost:8080",  // Prism API 서버 주소
    timeout = Duration.ofSeconds(5)     // 선택사항: 기본 5초
)
```

**Java**:
```java
import io.github.silbaram.prism.sdk.PrismClient;
import java.time.Duration;

PrismClient prismClient = new PrismClient(
    "http://localhost:8080",           // Prism API 서버 주소
    Duration.ofSeconds(5)              // 선택사항: 기본 5초
);
```

### 3. 사용하기

```kotlin
// 1. 사용자를 그룹에 할당
val response = prismClient.assign("user-123", "discount_experiment")
val variant = response.variant ?: "control"

// 2. 그룹에 따라 다른 로직 실행
when (variant) {
    "A" -> applyDiscount10()
    "B" -> applyDiscount20()
    else -> noDiscount()
}

// 3. 전환 기록 (주의: 안전한 방법 사용 필요!)
// prismClient.trackConversion() 직접 호출 금지!
// 아래 "안전한 전환 추적" 섹션 참고
```

---

## 핵심 개념

### 1. PrismClient (기본 클라이언트)

Prism API를 직접 호출하는 저수준 클라이언트입니다.

**제공 기능**:
- `assign()`: 사용자를 그룹에 할당
- `trackConversion()`: 전환 이벤트 기록 (**직접 호출 금지!**)

**주의사항**:
- ⚠️ `trackConversion()`을 직접 호출하면 통계 오염 발생
- ✅ 대신 `PrismExperimentClient`를 사용하세요

### 2. PrismExperimentClient (안전한 래퍼)

`PrismClient`를 감싸서 안전한 전환 추적을 제공합니다.

**제공 기능**:
- `assign()`: 사용자를 그룹에 할당 + `assigned` 플래그 계산
- `track()`: **할당된 사용자만** 전환 기록 (통계 오염 방지)
- `trackConversionIfAssigned()`: 편리한 헬퍼 메서드

**핵심 차이**:
```kotlin
// ❌ PrismClient 직접 사용 (통계 오염 위험)
prismClient.trackConversion("user-123", "experiment", "purchase")
// → 할당되지 않은 사용자도 기록됨!

// ✅ PrismExperimentClient 사용 (안전)
val outcome = experimentClient.assign("user-123", "experiment")
experimentClient.track(outcome, "purchase")
// → 할당된 사용자만 기록됨!
```

### 3. 통계 오염이란?

실험에 포함되지 않은 사용자의 데이터가 섞여서 통계가 부정확해지는 현상입니다.

**예시 상황**:
```
실험: discount_experiment
- A 그룹 100명 (10% 할인)
- B 그룹 100명 (20% 할인)

실험 외 사용자 1000명이 일반 가격으로 구매
→ 이들을 전환으로 기록하면 통계가 왜곡됨!
```

**잘못된 코드**:
```kotlin
fun purchase(userId: String) {
    // 구매 처리...

    // ❌ 모든 사용자의 전환을 기록
    prismClient.trackConversion(userId, "discount_experiment", "purchase")
    // → 실험에 없는 사용자도 기록됨 → 통계 오염!
}
```

**올바른 코드**:
```kotlin
fun purchase(userId: String) {
    // 구매 처리...

    // ✅ 할당 여부를 확인하고 기록
    experimentClient.trackConversionIfAssigned(
        userId,
        "discount_experiment",
        "purchase"
    )
    // → 할당된 사용자만 기록됨 → 통계 정확!
}
```

---

## 기본 사용법

A/B 테스트는 3단계로 이루어집니다.

### STEP 1: 클라이언트 생성

**싱글톤으로 관리하세요** (매번 생성하면 비효율적):

```kotlin
// Kotlin
object PrismClientProvider {
    val client: PrismClient by lazy {
        PrismClient(
            baseUrl = System.getenv("PRISM_API_URL") ?: "http://localhost:8080",
            timeout = Duration.ofSeconds(5)
        )
    }

    val experimentClient: PrismExperimentClient by lazy {
        PrismExperimentClient(client)
    }
}
```

```java
// Java
public class PrismClientProvider {
    private static final PrismClient CLIENT = new PrismClient(
        System.getenv().getOrDefault("PRISM_API_URL", "http://localhost:8080"),
        Duration.ofSeconds(5)
    );

    private static final PrismExperimentClient EXPERIMENT_CLIENT =
        new PrismExperimentClient(CLIENT);

    public static PrismClient getClient() {
        return CLIENT;
    }

    public static PrismExperimentClient getExperimentClient() {
        return EXPERIMENT_CLIENT;
    }
}
```

### STEP 2: 사용자를 그룹에 할당

```kotlin
import io.github.silbaram.prism.sdk.PrismClient

val client = PrismClientProvider.client

// 할당 요청
val response = client.assign(
    userId = "user-123",
    experimentKey = "discount_experiment"
)

// 결과 처리
when {
    response.variant != null -> {
        println("할당 성공: ${response.variant}")
    }
    else -> {
        println("할당 실패: ${response.resultMessage}")
        // 기본값 사용
    }
}
```

**AssignmentResponse 구조**:
```kotlin
data class AssignmentResponse(
    val userId: String,
    val experimentKey: String,
    val variant: String?,          // 할당된 그룹 (실패 시 null)
    val resultCode: String,         // "0000": 성공, "9999": 오류
    val resultMessage: String       // 응답 메시지
)
```

**결과 코드**:
- `0000`: 성공
- `1001`: 실험을 찾을 수 없음
- `1002`: 실험이 활성화되지 않음
- `9999`: 네트워크/서버 오류

### STEP 3: 그룹에 따라 다른 로직 실행

```kotlin
val variant = response.variant ?: "control"  // null이면 control

when (variant) {
    "A" -> {
        // A 그룹 로직
        applyDiscount(amount, 0.10)  // 10% 할인
    }
    "B" -> {
        // B 그룹 로직
        applyDiscount(amount, 0.20)  // 20% 할인
    }
    else -> {
        // control 그룹 로직 (기본값)
        noDiscount(amount)
    }
}
```

**Java 예제**:
```java
String variant = response.getVariant() != null
    ? response.getVariant()
    : "control";

switch (variant) {
    case "A":
        applyDiscount(amount, 0.10);
        break;
    case "B":
        applyDiscount(amount, 0.20);
        break;
    default:
        noDiscount(amount);
        break;
}
```

---

## 안전한 전환 추적

전환 추적은 **반드시** `PrismExperimentClient`를 사용해야 합니다.

### 왜 PrismExperimentClient를 사용해야 하나?

**문제 상황**:
```kotlin
// ❌ 잘못된 방법
val response = prismClient.assign("user-123", "experiment")
// ... 비즈니스 로직 ...

// 나중에 다른 곳에서 전환 기록
prismClient.trackConversion("user-123", "experiment", "purchase")
// → 문제: 이 사용자가 정말 할당됐는지 모름!
// → 할당 안 된 사용자도 기록되어 통계 오염!
```

**해결 방법**:
```kotlin
// ✅ 올바른 방법
val experimentClient = PrismExperimentClient(prismClient)

// 1. 할당 시 outcome 객체 받기 (assigned 플래그 포함)
val outcome = experimentClient.assign("user-123", "experiment")

// 2. outcome을 사용해서 전환 기록
experimentClient.track(outcome, "purchase")
// → outcome.assigned가 true일 때만 기록됨!
```

### PrismExperimentClient 사용법

#### 방법 1: assign + track (기본)

```kotlin
val experimentClient = PrismExperimentClient(prismClient)

// 1. 할당
val outcome = experimentClient.assign("user-123", "discount_experiment")
val variant = outcome.variant ?: "control"

// 2. 비즈니스 로직
val finalAmount = when (variant) {
    "A" -> amount * 0.9
    "B" -> amount * 0.8
    else -> amount
}

// 3. 전환 기록 (할당된 경우에만 자동으로 기록됨)
val tracked = experimentClient.track(outcome, "purchase")
if (tracked) {
    println("전환 기록 성공")
} else {
    println("할당 안 됨 → 전환 기록 스킵")
}
```

**AssignmentOutcome 구조**:
```kotlin
data class AssignmentOutcome(
    val userId: String,
    val experimentKey: String,
    val variant: String?,
    val assigned: Boolean,        // 🔑 핵심: 실제로 할당됐는지 여부
    val resultCode: String,
    val resultMessage: String
)
```

**assigned 플래그**:
- `true`: 실험에 성공적으로 할당됨 → 전환 기록 가능
- `false`: 실험이 없거나 오류 발생 → 전환 기록 스킵

#### 방법 2: trackConversionIfAssigned (편리함)

할당 여부를 저장하지 않아도 자동으로 확인해줍니다.

```kotlin
// 어딘가에서 할당 (outcome을 저장하지 않음)
experimentClient.assign("user-123", "experiment")

// ... 시간이 지남 ...

// 나중에 전환 기록 (자동으로 할당 여부 확인)
val tracked = experimentClient.trackConversionIfAssigned(
    userId = "user-123",
    experimentKey = "experiment",
    eventName = "purchase"
)

if (tracked) {
    println("할당된 사용자 → 전환 기록됨")
} else {
    println("할당 안 됨 → 전환 기록 스킵")
}
```

**내부 동작**:
1. 현재 요청 컨텍스트에서 할당 여부 확인
2. 할당됐으면 → `trackConversion()` 호출
3. 할당 안 됐으면 → 아무것도 안 함

**주의사항**:
- 같은 요청/스레드에서 `assign()` 호출 후 사용해야 함
- 다른 요청에서는 할당 여부를 알 수 없음

#### 방법 비교

| 방법 | 장점 | 단점 | 추천 상황 |
|------|------|------|----------|
| **assign + track** | 명확함, 안전함 | outcome 저장 필요 | 같은 메서드 내에서 할당과 전환 |
| **trackConversionIfAssigned** | 편리함 | 컨텍스트 의존 | 할당과 전환이 가까울 때 |

---

## 실제 사용 예제

### 예제 1: 이커머스 할인 실험

```kotlin
import io.github.silbaram.prism.sdk.PrismExperimentClient

class CheckoutService(
    private val experimentClient: PrismExperimentClient
) {
    fun processCheckout(userId: String, cartAmount: Int): CheckoutResult {
        // 1. 사용자를 할인 실험 그룹에 할당
        val outcome = experimentClient.assign(userId, "discount_experiment")
        val variant = outcome.variant ?: "control"

        // 2. 할당된 그룹에 따라 할인 적용
        val discount = when (variant) {
            "A" -> 0.10    // 10% 할인
            "B" -> 0.20    // 20% 할인
            else -> 0.0    // 할인 없음
        }

        val finalAmount = (cartAmount * (1 - discount)).toInt()

        // 3. 주문 처리
        val orderId = createOrder(userId, finalAmount)

        // 4. 전환 이벤트 기록 (할당된 사용자만)
        val tracked = experimentClient.track(outcome, "purchase")

        return CheckoutResult(
            orderId = orderId,
            originalAmount = cartAmount,
            finalAmount = finalAmount,
            discountApplied = discount,
            variant = variant,
            conversionTracked = tracked
        )
    }

    private fun createOrder(userId: String, amount: Int): String {
        // 실제 주문 생성 로직
        return "ORDER-${System.currentTimeMillis()}"
    }
}

data class CheckoutResult(
    val orderId: String,
    val originalAmount: Int,
    val finalAmount: Int,
    val discountApplied: Double,
    val variant: String,
    val conversionTracked: Boolean
)
```

**사용**:
```kotlin
val prismClient = PrismClient("http://localhost:8080")
val experimentClient = PrismExperimentClient(prismClient)
val checkoutService = CheckoutService(experimentClient)

// 테스트
val result = checkoutService.processCheckout("user-123", 10000)
println("주문 ID: ${result.orderId}")
println("최종 금액: ${result.finalAmount}원")
println("적용된 그룹: ${result.variant}")
println("전환 기록됨: ${result.conversionTracked}")
```

### 예제 2: Android 앱에서 UI 실험

```kotlin
// Android 앱
class MainActivity : AppCompatActivity() {
    private val prismClient = PrismClient("https://api.myapp.com")
    private val experimentClient = PrismExperimentClient(prismClient)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userId = getUserId()  // 저장된 사용자 ID

        // UI 실험
        val outcome = experimentClient.assign(userId, "button_color_test")
        val variant = outcome.variant ?: "control"

        // variant에 따라 다른 UI
        when (variant) {
            "red" -> {
                setContentView(R.layout.activity_main_red_button)
            }
            "blue" -> {
                setContentView(R.layout.activity_main_blue_button)
            }
            else -> {
                setContentView(R.layout.activity_main)
            }
        }

        // 버튼 클릭 시 전환 기록
        findViewById<Button>(R.id.ctaButton).setOnClickListener {
            // 전환 기록
            experimentClient.track(outcome, "button_click")

            // 실제 동작
            navigateToNextScreen()
        }
    }

    private fun getUserId(): String {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        var userId = prefs.getString("user_id", null)

        if (userId == null) {
            userId = UUID.randomUUID().toString()
            prefs.edit().putString("user_id", userId).apply()
        }

        return userId
    }
}
```

### 예제 3: CLI 도구에서 사용

```kotlin
// 명령줄 도구
fun main(args: Array<String>) {
    val prismClient = PrismClient("http://localhost:8080")
    val experimentClient = PrismExperimentClient(prismClient)

    val userId = args.getOrNull(0) ?: "anonymous"

    // 알고리즘 실험
    val outcome = experimentClient.assign(userId, "algorithm_test")
    val variant = outcome.variant ?: "control"

    println("사용자: $userId")
    println("할당된 그룹: $variant")

    // 알고리즘 선택
    val result = when (variant) {
        "fast" -> {
            println("빠른 알고리즘 실행 중...")
            runFastAlgorithm()
        }
        "accurate" -> {
            println("정확한 알고리즘 실행 중...")
            runAccurateAlgorithm()
        }
        else -> {
            println("기본 알고리즘 실행 중...")
            runDefaultAlgorithm()
        }
    }

    println("결과: $result")

    // 성공 시 전환 기록
    if (result.success) {
        experimentClient.track(outcome, "task_completed")
        println("전환 이벤트 기록됨")
    }
}
```

### 예제 4: 멀티스레드 환경

```kotlin
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    val prismClient = PrismClient("http://localhost:8080")
    val experimentClient = PrismExperimentClient(prismClient)

    // 스레드 풀
    val executor = Executors.newFixedThreadPool(10)

    // 1000명의 사용자 시뮬레이션
    repeat(1000) { i ->
        executor.submit {
            val userId = "user-$i"

            // 각 스레드에서 독립적으로 할당
            val outcome = experimentClient.assign(userId, "feature_test")
            val variant = outcome.variant ?: "control"

            // variant에 따른 처리
            val result = when (variant) {
                "new_feature" -> processWithNewFeature(userId)
                else -> processWithOldFeature(userId)
            }

            // 성공 시 전환 기록
            if (result.success) {
                experimentClient.track(outcome, "feature_used")
            }
        }
    }

    executor.shutdown()
    executor.awaitTermination(1, TimeUnit.MINUTES)

    println("모든 요청 완료")
}

data class ProcessResult(val success: Boolean)

fun processWithNewFeature(userId: String): ProcessResult {
    // 새 기능 처리
    return ProcessResult(true)
}

fun processWithOldFeature(userId: String): ProcessResult {
    // 기존 기능 처리
    return ProcessResult(true)
}
```

---

## 자주 묻는 질문

### Q1. PrismClient는 스레드 안전한가요?

**A**: 네, 스레드 안전합니다. 여러 스레드에서 동시에 사용해도 괜찮습니다.

```kotlin
// ✅ 싱글톤으로 만들어서 공유 가능
object PrismSingleton {
    val client = PrismClient("http://localhost:8080")
    val experimentClient = PrismExperimentClient(client)
}

// 여러 스레드에서 동시 사용
thread { PrismSingleton.client.assign("user-1", "exp-1") }
thread { PrismSingleton.client.assign("user-2", "exp-1") }
thread { PrismSingleton.client.assign("user-3", "exp-1") }
```

### Q2. 클라이언트를 매번 생성해도 되나요?

**A**: 가능하지만 **비효율적**입니다. 싱글톤으로 만들어서 재사용하세요.

```kotlin
// ❌ 비효율적 (매번 HTTP 클라이언트 생성)
fun checkout(userId: String) {
    val client = PrismClient("http://localhost:8080")  // 비효율!
    val response = client.assign(userId, "experiment")
}

// ✅ 효율적 (한 번만 생성)
object PrismClientProvider {
    val client = PrismClient("http://localhost:8080")
}

fun checkout(userId: String) {
    val response = PrismClientProvider.client.assign(userId, "experiment")
}
```

### Q3. 타임아웃을 변경할 수 있나요?

**A**: 네, 클라이언트 생성 시 설정할 수 있습니다.

```kotlin
import java.time.Duration

// 빠른 응답이 중요한 경우 (예: 웹 API)
val fastClient = PrismClient(
    baseUrl = "http://localhost:8080",
    timeout = Duration.ofSeconds(2)  // 2초
)

// 안정성이 중요한 경우 (예: 배치 작업)
val reliableClient = PrismClient(
    baseUrl = "http://localhost:8080",
    timeout = Duration.ofSeconds(10)  // 10초
)
```

### Q4. API 서버가 다운되면 어떻게 되나요?

**A**: 예외를 던지지 않고 `variant = null`을 반환합니다.

```kotlin
val response = client.assign("user-123", "experiment")

// 서버 다운 시
// response.variant = null
// response.resultCode = "9999"
// response.resultMessage = "Network error: ..."

// 비즈니스 로직은 정상 진행
val variant = response.variant ?: "control"  // control로 폴백
```

### Q5. 로그에 userId가 그대로 노출되나요?

**A**: 아니요, **자동으로 마스킹**됩니다.

```kotlin
// userId = "user-12345678"
client.assign("user-12345678", "experiment")

// 로그 출력:
// "할당 요청: userId=us***78, experimentKey=experiment"
// → 앞 2자리 + *** + 뒤 2자리만 노출
```

**마스킹 규칙**:
- 길이 <= 4: 전체 마스킹 (`****`)
- 길이 > 4: 앞 2자리 + `***` + 뒤 2자리

### Q6. 같은 사용자가 항상 같은 그룹에 배정되나요?

**A**: 네, userId를 기반으로 해시하므로 **항상 같은 그룹**에 배정됩니다.

```kotlin
// 오늘
val response1 = client.assign("user-123", "experiment")
println(response1.variant)  // "A"

// 내일
val response2 = client.assign("user-123", "experiment")
println(response2.variant)  // "A" (동일)

// 다음 달
val response3 = client.assign("user-123", "experiment")
println(response3.variant)  // "A" (동일)
```

### Q7. 여러 실험을 동시에 진행할 수 있나요?

**A**: 네, `experimentKey`로 구분됩니다.

```kotlin
val userId = "user-123"

// 실험 1: 할인 테스트
val outcome1 = experimentClient.assign(userId, "discount_test")
val discountVariant = outcome1.variant ?: "control"

// 실험 2: UI 색상 테스트
val outcome2 = experimentClient.assign(userId, "color_test")
val colorVariant = outcome2.variant ?: "control"

// 실험 3: 알고리즘 테스트
val outcome3 = experimentClient.assign(userId, "algorithm_test")
val algorithmVariant = outcome3.variant ?: "control"

// 각 실험은 독립적
println("할인: $discountVariant")      // 예: "A"
println("색상: $colorVariant")         // 예: "control"
println("알고리즘: $algorithmVariant") // 예: "B"
```

### Q8. Java에서도 사용할 수 있나요?

**A**: 네, 완벽하게 호환됩니다.

```java
import io.github.silbaram.prism.sdk.PrismClient;
import io.github.silbaram.prism.sdk.PrismExperimentClient;
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse;
import io.github.silbaram.prism.sdk.AssignmentOutcome;
import java.time.Duration;

public class Example {
    private static final PrismClient client =
        new PrismClient("http://localhost:8080", Duration.ofSeconds(5));

    private static final PrismExperimentClient experimentClient =
        new PrismExperimentClient(client);

    public void checkout(String userId, int amount) {
        // 할당
        AssignmentOutcome outcome = experimentClient.assign(userId, "discount_test");
        String variant = outcome.getVariant() != null
            ? outcome.getVariant()
            : "control";

        // 로직 실행
        int finalAmount;
        switch (variant) {
            case "A":
                finalAmount = (int)(amount * 0.9);
                break;
            case "B":
                finalAmount = (int)(amount * 0.8);
                break;
            default:
                finalAmount = amount;
                break;
        }

        // 주문 처리
        processOrder(userId, finalAmount);

        // 전환 기록
        boolean tracked = experimentClient.track(outcome, "purchase");
        System.out.println("전환 기록됨: " + tracked);
    }

    private void processOrder(String userId, int amount) {
        // 주문 처리 로직
    }
}
```

### Q9. 전환 이벤트를 여러 번 기록해도 되나요?

**A**: 네, 같은 사용자가 여러 번 전환해도 각각 기록됩니다.

```kotlin
val outcome = experimentClient.assign("user-123", "product_test")

// 장바구니 담기
experimentClient.track(outcome, "add_to_cart")

// 결제 시도
experimentClient.track(outcome, "checkout_started")

// 구매 완료
experimentClient.track(outcome, "purchase")

// 모두 개별적으로 기록됨
```

### Q10. 비동기로 호출해도 되나요?

**A**: 네, 괜찮습니다. SDK 내부에서 비동기 처리를 합니다.

```kotlin
import kotlinx.coroutines.*

suspend fun asyncExample() = coroutineScope {
    val client = PrismClientProvider.client
    val experimentClient = PrismClientProvider.experimentClient

    // 병렬로 여러 사용자 할당
    val jobs = (1..100).map { i ->
        async {
            val userId = "user-$i"
            val outcome = experimentClient.assign(userId, "experiment")

            // 로직 실행
            processUser(userId, outcome.variant ?: "control")

            // 전환 기록
            experimentClient.track(outcome, "completed")
        }
    }

    // 모든 작업 완료 대기
    jobs.awaitAll()
}
```

---

## 오류 처리

SDK는 Fail-Safe 설계로 절대 예외를 던지지 않습니다.

### 오류 발생 시 동작

```kotlin
// 어떤 오류가 나도 예외를 던지지 않음
val response = client.assign("user-123", "experiment")

// 응답 객체는 항상 반환됨
println("variant: ${response.variant}")         // null일 수 있음
println("resultCode: ${response.resultCode}")   // "9999"는 오류
println("resultMessage: ${response.resultMessage}")
```

### 결과 코드

| 코드 | 의미 | variant | 대응 |
|------|------|---------|------|
| `0000` | 성공 | 할당됨 | 정상 진행 |
| `1001` | 실험 없음 | `null` | control로 폴백 |
| `1002` | 실험 비활성화 | `null` | control로 폴백 |
| `1003` | 타겟팅 불일치 | `null` | control로 폴백 |
| `9999` | 네트워크/서버 오류 | `null` | control로 폴백 |

### 권장 오류 처리 패턴

```kotlin
fun processWithExperiment(userId: String, amount: Int): Int {
    val response = client.assign(userId, "discount_experiment")

    // 1. variant 기본값 처리
    val variant = response.variant ?: "control"

    // 2. 오류 로깅 (선택사항)
    if (response.resultCode != "0000") {
        logger.warn(
            "실험 할당 실패: userId={}, code={}, message={}",
            maskUserId(userId),
            response.resultCode,
            response.resultMessage
        )
    }

    // 3. 비즈니스 로직은 항상 실행
    return when (variant) {
        "A" -> (amount * 0.9).toInt()
        "B" -> (amount * 0.8).toInt()
        else -> amount
    }
}
```

---

## 테스트

```bash
# 단위 테스트 실행
./gradlew :prism-sdk:test

# 빌드
./gradlew :prism-sdk:build

# 특정 테스트만 실행
./gradlew :prism-sdk:test --tests "PrismClientTest"
```

---

## 다음 단계

### Spring Boot를 사용 중이라면?

[Prism Spring Boot Starter](../prism-spring-boot-starter/README.md)를 사용하면 더 편리합니다:
- 자동 설정
- 어노테이션 기반
- AOP 지원
- Spring Bean 자동 등록

### 더 알아보기

- [Prism Admin 가이드](../prism-admin/README.md): 실험 생성 및 관리
- [Prism API 문서](../prism-api/README.md): API 서버 설정
- [Prism 메인 문서](../README.md): 전체 아키텍처

---

## 요약

### 핵심 포인트

1. **Fail-Safe**: 어떤 오류가 나도 비즈니스 로직은 계속 실행됨
2. **PrismExperimentClient 사용 필수**: 통계 오염 방지
3. **싱글톤으로 관리**: 클라이언트를 재사용하여 효율적으로 사용
4. **항상 기본값 처리**: `?: "control"`로 오류 대응

### 빠른 체크리스트

- [ ] Gradle 의존성 추가
- [ ] PrismClient 싱글톤 생성
- [ ] PrismExperimentClient로 래핑
- [ ] assign() 호출하여 variant 받기
- [ ] variant에 따라 로직 분기 (기본값 처리)
- [ ] track() 또는 trackConversionIfAssigned()로 전환 기록
- [ ] ⚠️ PrismClient.trackConversion() 직접 호출 금지!

이제 Java/Kotlin 앱에서 안전하게 A/B 테스트를 시작할 수 있습니다! 🚀

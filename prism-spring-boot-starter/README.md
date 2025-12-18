# Prism Spring Boot Starter

Spring Boot 애플리케이션에서 A/B 테스트를 쉽고 안전하게 할 수 있도록 도와주는 라이브러리입니다.

## 목차
- [이 라이브러리가 하는 일](#이-라이브러리가-하는-일)
- [빠른 시작](#빠른-시작)
- [핵심 개념 이해하기](#핵심-개념-이해하기)
- [기본 사용법](#기본-사용법)
- [고급 사용법](#고급-사용법)
- [자주 묻는 질문](#자주-묻는-질문)

---

## 이 라이브러리가 하는 일

A/B 테스트를 할 때 필요한 두 가지 핵심 기능을 제공합니다:

### 1. 그룹 분배 (Assignment)
사용자를 여러 그룹(variant)으로 자동 분배합니다.
- 예: 100명의 사용자를 A 그룹 50명, B 그룹 50명으로 나눕니다
- 같은 사용자는 항상 같은 그룹에 배정됩니다

### 2. 전환 추적 (Conversion Tracking)
각 그룹별로 목표 달성 여부를 기록합니다.
- 예: A 그룹 중 20명이 구매, B 그룹 중 30명이 구매
- **중요**: 실제로 그룹에 배정된 사용자만 추적하여 통계가 정확합니다

---

## 빠른 시작

### 1. 의존성 추가

```kotlin
dependencies {
    implementation("io.github.silbaram.prism:prism-spring-boot-starter:0.0.1-SNAPSHOT")
}
```

### 2. 설정 파일 작성

`application.yml`에 Prism API 서버 주소를 설정합니다:

```yaml
prism:
  client:
    url: http://localhost:8080   # Prism API 서버 주소
    timeout: 5s                  # 선택사항: 기본 5초
```

이것으로 설정 완료! 이제 코드에서 A/B 테스트를 시작할 수 있습니다.

---

## 핵심 개념 이해하기

A/B 테스트를 제대로 사용하려면 몇 가지 개념을 알아야 합니다.

### 그룹 할당 (Assignment)이란?

사용자를 여러 그룹(variant)으로 나누는 것입니다.

**예시 상황**: 할인율 실험
- **A 그룹**: 10% 할인 제공
- **B 그룹**: 20% 할인 제공
- **control 그룹**: 할인 없음 (기본값)

```
사용자 ID: "user-123" → Prism → A 그룹으로 할당
사용자 ID: "user-456" → Prism → B 그룹으로 할당
사용자 ID: "user-789" → Prism → control 그룹으로 할당
```

**중요 특징**:
- 같은 사용자 ID는 항상 같은 그룹에 배정됩니다
- 할당에 실패하거나 실험이 없으면 `null`이 반환됩니다

### 전환 추적 (Conversion Tracking)이란?

각 그룹별로 목표 달성 여부를 기록하는 것입니다.

**예시**: 구매 전환
- A 그룹 100명 중 15명 구매 → 전환율 15%
- B 그룹 100명 중 25명 구매 → 전환율 25%
- **결론**: B 그룹(20% 할인)이 더 효과적!

### 통계 오염이란?

실험에 포함되지 않은 사용자의 데이터가 섞여서 통계가 부정확해지는 현상입니다.

**나쁜 예** (통계 오염):
```kotlin
// ❌ 잘못된 방법
fun purchase(userId: String) {
    // ... 구매 처리 ...
    prismClient.trackConversion(userId, "discount_test", "purchase")  // 모든 사용자 기록
}
```
→ 실험에 포함되지 않은 사용자도 기록되어 통계가 왜곡됩니다!

**좋은 예** (통계 오염 방지):
```kotlin
// ✅ 올바른 방법
fun purchase(userId: String) {
    // ... 구매 처리 ...
    conversionTracker.trackConversionSafe(userId, "discount_test", "purchase")  // 할당된 사용자만 기록
}
```
→ 이 라이브러리는 실제로 그룹에 배정된 사용자만 자동으로 기록합니다!

---

## 기본 사용법

A/B 테스트는 3단계로 이루어집니다.

### STEP 1: 사용자를 그룹에 할당하기

`@PrismExperiment` 어노테이션을 사용하면 메서드가 호출될 때 자동으로 그룹을 할당합니다.

```kotlin
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import org.springframework.stereotype.Service

@Service
class DiscountService {

    @PrismExperiment(experimentKey = "discount_ab_test")
    fun calculateDiscount(@PrismUserId userId: String, amount: Int): Int {
        // 이 메서드가 호출되면 자동으로 userId를 기반으로 그룹이 할당됩니다
        // 다음 단계에서 할당된 그룹 정보를 사용합니다
    }
}
```

**설명**:
- `experimentKey`: 실험을 구분하는 고유한 이름
- `@PrismUserId`: 어떤 파라미터가 사용자 ID인지 표시

### STEP 2: 할당된 그룹에 따라 다른 로직 실행하기

`PrismContext.getCurrentVariant()`로 현재 사용자가 어느 그룹인지 확인하고, 그에 맞는 로직을 실행합니다.

```kotlin
import io.github.silbaram.prism.starter.aop.PrismContext

@Service
class DiscountService {

    @PrismExperiment(experimentKey = "discount_ab_test")
    fun calculateDiscount(@PrismUserId userId: String, amount: Int): Int {
        // 1. 할당된 그룹(variant) 확인
        val variant = PrismContext.getCurrentVariant() ?: "control"

        // 2. 그룹에 따라 다른 할인율 적용
        return when (variant) {
            "A" -> (amount * 0.9).toInt()   // A 그룹: 10% 할인
            "B" -> (amount * 0.8).toInt()   // B 그룹: 20% 할인
            else -> amount                  // control: 할인 없음
        }
    }
}
```

**설명**:
- `PrismContext.getCurrentVariant()`: 현재 사용자가 할당된 그룹을 반환
  - 성공: "A", "B", "control" 등
  - 실패 또는 실험 없음: `null` (이 경우 `?: "control"`로 기본값 처리)

### STEP 3: 전환 이벤트 추적하기

목표를 달성한 사용자를 기록합니다. `PrismConversionTracker`를 사용하면 안전하게 추적할 수 있습니다.

```kotlin
import io.github.silbaram.prism.starter.service.PrismConversionTracker

@Service
class CheckoutService(
    private val discountService: DiscountService,
    private val conversionTracker: PrismConversionTracker
) {

    @PrismExperiment(experimentKey = "discount_ab_test")
    fun purchase(@PrismUserId userId: String, amount: Int) {
        // 1. 할인 적용
        val discountedAmount = discountService.calculateDiscount(userId, amount)

        // 2. 구매 처리
        processPurchase(userId, discountedAmount)

        // 3. 전환 이벤트 추적 (실제로 할당된 사용자만 기록됨)
        conversionTracker.trackConversionSafe(
            userId = userId,
            experimentKey = "discount_ab_test",
            eventName = "purchase"
        )
    }

    private fun processPurchase(userId: String, amount: Int) {
        // 실제 구매 로직...
    }
}
```

**설명**:
- `trackConversionSafe()`: 안전한 전환 추적
  - 실제로 그룹에 할당된 사용자만 기록
  - 할당되지 않은 사용자는 자동으로 제외 (통계 오염 방지)
- `eventName`: 추적할 이벤트 이름 (예: "purchase", "signup", "click" 등)

### 전체 예제: 할인 A/B 테스트

```kotlin
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.github.silbaram.prism.starter.aop.PrismContext
import io.github.silbaram.prism.starter.service.PrismConversionTracker
import org.springframework.stereotype.Service

@Service
class ShoppingService(
    private val conversionTracker: PrismConversionTracker
) {

    @PrismExperiment(experimentKey = "discount_ab_test")
    fun checkout(@PrismUserId userId: String, cartAmount: Int): CheckoutResult {
        // STEP 1: 자동으로 그룹 할당됨 (@PrismExperiment가 처리)

        // STEP 2: 할당된 그룹에 따라 다른 할인 적용
        val variant = PrismContext.getCurrentVariant() ?: "control"
        val finalAmount = when (variant) {
            "A" -> (cartAmount * 0.9).toInt()   // 10% 할인
            "B" -> (cartAmount * 0.8).toInt()   // 20% 할인
            else -> cartAmount                   // 할인 없음
        }

        // 구매 처리
        val orderId = processOrder(userId, finalAmount)

        // STEP 3: 전환 이벤트 추적 (할당된 사용자만)
        conversionTracker.trackConversionSafe(
            userId = userId,
            experimentKey = "discount_ab_test",
            eventName = "purchase"
        )

        return CheckoutResult(orderId, finalAmount, variant)
    }

    private fun processOrder(userId: String, amount: Int): String {
        // 실제 주문 처리 로직
        return "ORDER-${System.currentTimeMillis()}"
    }
}

data class CheckoutResult(
    val orderId: String,
    val paidAmount: Int,
    val appliedVariant: String
)
```

**결과**:
- A 그룹 사용자: 10% 할인 + 구매 시 전환 이벤트 기록
- B 그룹 사용자: 20% 할인 + 구매 시 전환 이벤트 기록
- control 그룹: 할인 없음 + 구매 시 전환 이벤트 기록

통계 분석 결과:
```
A 그룹: 전환율 12% (할인 때문에 구매 증가)
B 그룹: 전환율 18% (더 큰 할인으로 구매 더 증가)
control: 전환율 8% (기본 전환율)
```

### 컨트롤러에서 사용하기

REST API에서도 동일하게 사용할 수 있습니다.

```kotlin
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.github.silbaram.prism.starter.aop.PrismContext
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/products")
class ProductController {

    @GetMapping("/{id}")
    @PrismExperiment(experimentKey = "product_ui_test")
    fun getProduct(
        @PathVariable id: String,
        @CookieValue("user_id") @PrismUserId userId: String
    ): ProductResponse {
        // 할당된 그룹에 따라 다른 UI 버전 반환
        val variant = PrismContext.getCurrentVariant() ?: "control"

        val product = findProduct(id)

        return when (variant) {
            "new_design" -> ProductResponse(
                id = product.id,
                name = product.name,
                description = product.detailedDescription,  // 긴 설명
                imageSize = "large"                         // 큰 이미지
            )
            else -> ProductResponse(
                id = product.id,
                name = product.name,
                description = product.shortDescription,     // 짧은 설명
                imageSize = "medium"                        // 중간 이미지
            )
        }
    }

    private fun findProduct(id: String): Product {
        // 실제 상품 조회 로직
        return Product(id, "상품명", "짧은설명", "긴설명")
    }
}

data class Product(val id: String, val name: String, val shortDescription: String, val detailedDescription: String)
data class ProductResponse(val id: String, val name: String, val description: String, val imageSize: String)
```

---

## 고급 사용법

코드가 복잡해지면 더 깔끔한 방법으로 variant별 로직을 분리할 수 있습니다.

### 방법 1: 메서드 레벨 라우팅 (@PrismVariantMethod)

**언제 사용?**
- variant별 로직이 명확히 구분되는 경우
- 같은 클래스 안에서 관리하고 싶을 때
- 코드가 비교적 간단할 때

**어떻게 동작?**
1. variant별로 메서드를 따로 만듭니다
2. `@PrismVariantMethod`로 각 메서드가 어떤 variant용인지 표시합니다
3. `PrismVariantMethodRouter`가 자동으로 적절한 메서드를 선택해서 실행합니다

```kotlin
import io.github.silbaram.prism.starter.annotation.PrismVariantMethod
import io.github.silbaram.prism.starter.routing.PrismVariantMethodRouter
import io.github.silbaram.prism.starter.service.PrismConversionTracker
import org.springframework.stereotype.Service

@Service
class CheckoutService(
    private val router: PrismVariantMethodRouter,
    private val conversionTracker: PrismConversionTracker
) {

    // 이 메서드가 호출되면 자동으로 적절한 variant 메서드가 실행됩니다
    fun processCheckout(userId: String, amount: Int): Int {
        // 1. 라우터가 userId를 기반으로 그룹을 할당하고 해당 메서드를 실행
        val result = router.route(this, userId, "checkout_discount", amount)

        // 2. 전환 추적 (필요한 경우)
        if (result > 0) {
            conversionTracker.trackConversionSafe(userId, "checkout_discount", "purchase")
        }

        return result
    }

    // A 그룹 전용 메서드
    @PrismVariantMethod(variant = "A", experimentKey = "checkout_discount")
    fun processCheckoutA(amount: Int): Int {
        println("A 그룹 실행: 10% 할인")
        return (amount * 0.9).toInt()
    }

    // B 그룹 전용 메서드
    @PrismVariantMethod(variant = "B", experimentKey = "checkout_discount")
    fun processCheckoutB(amount: Int): Int {
        println("B 그룹 실행: 20% 할인")
        return (amount * 0.8).toInt()
    }

    // control 그룹 전용 메서드
    @PrismVariantMethod(variant = "control", experimentKey = "checkout_discount")
    fun processCheckoutControl(amount: Int): Int {
        println("control 그룹 실행: 할인 없음")
        return amount
    }
}
```

**장점**:
- if-else나 when 문을 사용하지 않아도 됨
- variant별 로직이 메서드 단위로 깔끔히 분리됨
- 같은 클래스에 모아두어 관리가 쉬움

**단점**:
- 복잡한 로직은 메서드가 길어질 수 있음

### 방법 2: 클래스 레벨 전략 패턴 (@PrismStrategy)

**언제 사용?**
- variant별 로직이 복잡할 때
- 각 variant가 여러 메서드를 가질 때
- 의존성 주입이 필요할 때
- 완전히 독립적인 구현을 원할 때

**어떻게 동작?**
1. 공통 인터페이스를 정의합니다
2. variant별로 인터페이스를 구현하는 클래스를 만듭니다
3. `@PrismStrategy`로 각 클래스가 어떤 variant용인지 표시합니다
4. `PrismStrategyResolver`가 자동으로 적절한 구현체를 선택합니다

```kotlin
import io.github.silbaram.prism.starter.annotation.PrismStrategy
import io.github.silbaram.prism.starter.strategy.PrismStrategyResolver
import io.github.silbaram.prism.starter.service.PrismConversionTracker
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component

// 1단계: 공통 인터페이스 정의
interface PricingStrategy {
    fun calculatePrice(amount: Int): Int
    fun getDiscountMessage(): String
}

// 2단계: variant별 구현체 정의

// Premium 그룹 구현체
@PrismStrategy(variant = "premium", experimentKey = "pricing_experiment")
@Component
class PremiumPricingStrategy : PricingStrategy {
    override fun calculatePrice(amount: Int): Int {
        return (amount * 0.85).toInt()  // 15% 할인
    }

    override fun getDiscountMessage(): String {
        return "프리미엄 회원 특별 할인 15%"
    }
}

// Standard 그룹 구현체
@PrismStrategy(variant = "standard", experimentKey = "pricing_experiment")
@Component
class StandardPricingStrategy : PricingStrategy {
    override fun calculatePrice(amount: Int): Int {
        return (amount * 0.95).toInt()  // 5% 할인
    }

    override fun getDiscountMessage(): String {
        return "첫 구매 고객 할인 5%"
    }
}

// Control 그룹 구현체
@PrismStrategy(variant = "control", experimentKey = "pricing_experiment")
@Component
class ControlPricingStrategy : PricingStrategy {
    override fun calculatePrice(amount: Int): Int {
        return amount  // 할인 없음
    }

    override fun getDiscountMessage(): String {
        return "정상가"
    }
}

// 3단계: 서비스에서 사용
@Service
class ProductService(
    private val strategyResolver: PrismStrategyResolver,
    private val conversionTracker: PrismConversionTracker
) {

    fun checkout(userId: String, amount: Int): CheckoutResult {
        // 1. userId를 기반으로 적절한 전략 구현체 선택
        val strategy = strategyResolver.resolve<PricingStrategy>(userId, "pricing_experiment")

        // 2. 선택된 전략으로 가격 계산
        val finalAmount = strategy.calculatePrice(amount)
        val message = strategy.getDiscountMessage()

        // 3. 주문 처리
        val orderId = processOrder(userId, finalAmount)

        // 4. 전환 추적
        if (orderId.isNotEmpty()) {
            conversionTracker.trackConversionSafe(userId, "pricing_experiment", "purchase")
        }

        return CheckoutResult(orderId, finalAmount, message)
    }

    private fun processOrder(userId: String, amount: Int): String {
        return "ORDER-${System.currentTimeMillis()}"
    }
}

data class CheckoutResult(val orderId: String, val amount: Int, val discountMessage: String)
```

**장점**:
- 각 variant의 로직이 완전히 분리됨 (클래스 단위)
- 복잡한 로직도 깔끔하게 관리 가능
- 각 전략 클래스에서 의존성 주입 가능 (Spring Bean)
- 테스트하기 쉬움 (각 전략을 독립적으로 테스트)

**단점**:
- 간단한 로직에는 오버엔지니어링일 수 있음
- 파일이 여러 개 생김

### 두 방법 비교

| 구분 | 메서드 라우팅 | 전략 패턴 |
|------|-------------|----------|
| **복잡도** | 간단 | 복잡 |
| **파일 수** | 1개 | 여러 개 |
| **적합한 경우** | 로직이 간단할 때 | 로직이 복잡할 때 |
| **의존성 주입** | 제한적 | 자유로움 |
| **코드 분리** | 메서드 단위 | 클래스 단위 |
| **추천** | 시작할 때 | 확장할 때 |

---

## 자주 묻는 질문

### Q1. userId가 없는 익명 사용자는 어떻게 처리하나요?

**A**: 임시 ID를 생성하거나 쿠키에 저장된 ID를 사용하세요.

```kotlin
@GetMapping("/products/{id}")
@PrismExperiment(experimentKey = "product_ui_test")
fun getProduct(
    @PathVariable id: String,
    @CookieValue(value = "anonymous_id", required = false) @PrismUserId userId: String?
): ProductResponse {
    // userId가 없으면 실험에 포함되지 않음
    val variant = PrismContext.getCurrentVariant() ?: "control"
    // ...
}
```

또는 컨트롤러에서 임시 ID 생성:

```kotlin
@GetMapping("/products/{id}")
fun getProduct(
    @PathVariable id: String,
    @CookieValue(value = "user_id", required = false) userId: String?,
    response: HttpServletResponse
): ProductResponse {
    val actualUserId = userId ?: UUID.randomUUID().toString().also {
        // 쿠키에 저장
        response.addCookie(Cookie("user_id", it).apply { maxAge = 365 * 24 * 60 * 60 })
    }

    return productService.getProduct(actualUserId, id)
}
```

### Q2. 실험이 없거나 API 호출이 실패하면 어떻게 되나요?

**A**: `PrismContext.getCurrentVariant()`가 `null`을 반환합니다. 항상 기본값을 처리하세요.

```kotlin
val variant = PrismContext.getCurrentVariant() ?: "control"  // ✅ 기본값 처리

// 또는 더 명시적으로
val variant = PrismContext.getCurrentVariant()
if (variant == null) {
    logger.warn("실험 할당 실패, control 그룹으로 처리")
    return processControlLogic()
}
```

### Q3. 같은 사용자가 항상 같은 그룹에 배정되나요?

**A**: 네, Prism은 해시 기반 분배를 사용하므로 같은 userId는 항상 같은 그룹에 배정됩니다.

```
user-123 → 항상 A 그룹
user-456 → 항상 B 그룹
user-789 → 항상 control 그룹
```

### Q4. 여러 실험을 동시에 진행할 수 있나요?

**A**: 네, `experimentKey`로 구분됩니다.

```kotlin
@Service
class MarketingService(
    private val conversionTracker: PrismConversionTracker
) {

    @PrismExperiment(experimentKey = "discount_test")
    fun applyDiscount(@PrismUserId userId: String, amount: Int): Int {
        val variant = PrismContext.getCurrentVariant() ?: "control"
        // discount_test 실험의 variant
    }

    @PrismExperiment(experimentKey = "ui_color_test")
    fun getButtonColor(@PrismUserId userId: String): String {
        val variant = PrismContext.getCurrentVariant() ?: "control"
        // ui_color_test 실험의 variant (위와 독립적)
    }
}
```

### Q5. 전환 이벤트를 여러 번 호출해도 되나요?

**A**: 네, 같은 사용자가 여러 번 전환해도 각각 기록됩니다.

```kotlin
// 장바구니 담기
conversionTracker.trackConversionSafe(userId, "product_test", "add_to_cart")

// 구매 (같은 사용자가 여러 번 구매 가능)
conversionTracker.trackConversionSafe(userId, "product_test", "purchase")
conversionTracker.trackConversionSafe(userId, "product_test", "purchase")  // 또 구매
```

### Q6. @PrismExperiment를 사용하지 않고 직접 할당하려면?

**A**: `PrismExperimentClient`를 직접 사용할 수 있습니다.

```kotlin
import io.github.silbaram.prism.sdk.PrismExperimentClient

@Service
class CustomService(
    private val prismExperimentClient: PrismExperimentClient,
    private val conversionTracker: PrismConversionTracker
) {

    fun processCustomLogic(userId: String) {
        // 1. 직접 할당
        val outcome = prismExperimentClient.assign(userId, "my_experiment")
        val variant = outcome.variant ?: "control"

        // 2. variant에 따라 로직 실행
        when (variant) {
            "A" -> handleA()
            "B" -> handleB()
            else -> handleControl()
        }

        // 3. 전환 추적 (outcome을 직접 사용)
        if (outcome.assigned) {
            prismExperimentClient.track(outcome, "custom_event")
        }
    }
}
```

### Q7. 통계 오염을 방지하려면 꼭 trackConversionSafe를 사용해야 하나요?

**A**: 네, 매우 중요합니다!

```kotlin
// ❌ 절대 금지: PrismClient.trackConversion 직접 호출
prismClient.trackConversion(userId, experimentKey, "purchase")
// → 할당되지 않은 사용자도 기록되어 통계 왜곡

// ✅ 올바른 방법 1: PrismConversionTracker 사용
conversionTracker.trackConversionSafe(userId, experimentKey, "purchase")

// ✅ 올바른 방법 2: PrismExperimentClient 사용
val outcome = prismExperimentClient.assign(userId, experimentKey)
if (outcome.assigned) {
    prismExperimentClient.track(outcome, "purchase")
}
```

### Q8. 비동기로 전환 추적을 해도 되나요?

**A**: 네, 괜찮습니다. 다만 할당 정보는 유지되어야 합니다.

```kotlin
@Service
class AsyncCheckoutService(
    private val conversionTracker: PrismConversionTracker
) {

    @PrismExperiment(experimentKey = "checkout_test")
    suspend fun checkout(@PrismUserId userId: String, amount: Int) = coroutineScope {
        // 비동기 처리
        val orderId = async { processOrder(amount) }
        val payment = async { processPayment(amount) }

        awaitAll(orderId, payment)

        // 전환 추적 (메인 스레드가 아니어도 OK)
        conversionTracker.trackConversionSafe(userId, "checkout_test", "purchase")
    }
}
```

---

## 자동 구성되는 Spring Bean

이 라이브러리를 추가하면 다음 Bean들이 자동으로 등록됩니다:

| Bean | 설명 |
|------|------|
| `PrismExperimentClient` | 안전한 할당 및 전환 추적 |
| `PrismConversionTracker` | 안전한 전환 추적 전용 |
| `PrismExperimentAspect` | @PrismExperiment 처리 |
| `PrismVariantMethodRouter` | 메서드 라우팅 |
| `PrismStrategyResolver` | 전략 패턴 지원 |

직접 주입받아 사용할 수 있습니다:

```kotlin
@Service
class MyService(
    private val experimentClient: PrismExperimentClient,
    private val conversionTracker: PrismConversionTracker,
    private val router: PrismVariantMethodRouter,
    private val resolver: PrismStrategyResolver
) {
    // ...
}
```

---

## 테스트

```bash
# 전체 테스트
./gradlew :prism-spring-boot-starter:test

# 빌드
./gradlew :prism-spring-boot-starter:build
```

---

## 다음 단계

1. **실험 생성**: Prism Admin에서 실험을 생성하고 variant 비율을 설정하세요
2. **코드 배포**: 이 라이브러리를 사용하여 A/B 테스트 코드를 배포하세요
3. **결과 분석**: Prism Admin에서 각 그룹의 전환율을 확인하세요
4. **의사결정**: 통계적으로 유의미한 결과를 기반으로 최종 버전을 선택하세요

더 자세한 정보는 [Prism 메인 문서](../README.md)를 참고하세요.

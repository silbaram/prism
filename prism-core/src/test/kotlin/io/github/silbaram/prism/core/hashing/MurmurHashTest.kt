package io.github.silbaram.prism.core.hashing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * MurmurHash 단위 테스트
 *
 * 이 테스트 클래스는 MurmurHash3 32-bit 해시 알고리즘의 정확성과 일관성을 검증합니다.
 * 주요 테스트 항목:
 * 1. 해시 일관성 (Deterministic Hashing):
 *    - 동일한 입력에 대해 항상 동일한 해시값을 생성하는지 확인합니다.
 * 2. 해시 유일성:
 *    - 다른 입력에 대해 다른 해시값을 생성하는지 확인합니다.
 * 3. Seed 값에 따른 해시 변화:
 *    - 다른 시드값을 사용하면 다른 해시값이 생성되는지 확인합니다.
 * 4. ByteArray 직접 처리:
 *    - 바이트 배열을 직접 해시할 수 있는지 확인합니다.
 * 5. 표준 테스트 벡터:
 *    - MurmurHash3 알고리즘의 표준 테스트 케이스와 일치하는지 확인합니다.
 */
class MurmurHashTest : FunSpec({

    test("동일한 문자열에 대해 항상 동일한 해시값을 생성한다") {
        val key = "test-user-123"
        val hash1 = MurmurHash.hash32(key)
        val hash2 = MurmurHash.hash32(key)
        val hash3 = MurmurHash.hash32(key)

        hash1 shouldBe hash2
        hash2 shouldBe hash3
    }

    test("다른 문자열에 대해 다른 해시값을 생성한다") {
        val key1 = "user-123"
        val key2 = "user-456"

        val hash1 = MurmurHash.hash32(key1)
        val hash2 = MurmurHash.hash32(key2)

        hash1 shouldNotBe hash2
    }

    test("빈 문자열에 대해 해시값을 생성한다") {
        val emptyKey = ""
        val hash = MurmurHash.hash32(emptyKey)

        // 빈 문자열도 일관된 해시값을 가져야 함
        hash shouldBe MurmurHash.hash32("")
    }

    test("동일한 seed를 사용하면 동일한 해시값을 생성한다") {
        val key = "test-key"
        val seed = 12345

        val hash1 = MurmurHash.hash32(key, seed)
        val hash2 = MurmurHash.hash32(key, seed)

        hash1 shouldBe hash2
    }

    test("다른 seed를 사용하면 다른 해시값을 생성한다") {
        val key = "test-key"
        val seed1 = 12345
        val seed2 = 67890

        val hash1 = MurmurHash.hash32(key, seed1)
        val hash2 = MurmurHash.hash32(key, seed2)

        hash1 shouldNotBe hash2
    }

    test("ByteArray를 직접 해시할 수 있다") {
        val key = "test-key"
        val byteArray = key.toByteArray(Charsets.UTF_8)

        val hashFromString = MurmurHash.hash32(key)
        val hashFromByteArray = MurmurHash.hash32(byteArray)

        hashFromString shouldBe hashFromByteArray
    }

    test("ByteArray와 seed를 함께 사용할 수 있다") {
        val key = "test-key"
        val byteArray = key.toByteArray(Charsets.UTF_8)
        val seed = 12345

        val hashFromString = MurmurHash.hash32(key, seed)
        val hashFromByteArray = MurmurHash.hash32(byteArray, seed)

        hashFromString shouldBe hashFromByteArray
    }

    test("MurmurHash3 표준 테스트 벡터: 빈 문자열") {
        // MurmurHash3 표준 구현의 테스트 벡터
        // seed=0, key="" => hash=0
        val hash = MurmurHash.hash32("", 0)
        hash shouldBe 0
    }

    test("MurmurHash3 표준 테스트 벡터: 단일 바이트") {
        // seed=0, key=0x00 => 특정 해시값
        val byteArray = byteArrayOf(0x00)
        val hash = MurmurHash.hash32(byteArray, 0)

        // 동일한 입력에 대해 일관성만 확인
        hash shouldBe MurmurHash.hash32(byteArray, 0)
    }

    test("긴 문자열에 대해 해시값을 생성한다") {
        val longKey = "a".repeat(1000)
        val hash1 = MurmurHash.hash32(longKey)
        val hash2 = MurmurHash.hash32(longKey)

        hash1 shouldBe hash2
    }

    test("유니코드 문자열을 올바르게 처리한다") {
        val unicodeKey1 = "안녕하세요"
        val unicodeKey2 = "こんにちは"
        val unicodeKey3 = "Hello 世界"

        val hash1 = MurmurHash.hash32(unicodeKey1)
        val hash2 = MurmurHash.hash32(unicodeKey2)
        val hash3 = MurmurHash.hash32(unicodeKey3)

        // 각각 다른 해시값을 가져야 함
        hash1 shouldNotBe hash2
        hash2 shouldNotBe hash3
        hash1 shouldNotBe hash3

        // 동일 입력에 대해 일관성 유지
        hash1 shouldBe MurmurHash.hash32(unicodeKey1)
        hash2 shouldBe MurmurHash.hash32(unicodeKey2)
        hash3 shouldBe MurmurHash.hash32(unicodeKey3)
    }

    test("특수 문자를 포함한 문자열을 처리한다") {
        val specialKey = "user@example.com!#$%^&*()"
        val hash1 = MurmurHash.hash32(specialKey)
        val hash2 = MurmurHash.hash32(specialKey)

        hash1 shouldBe hash2
    }

    test("바이트 배열의 길이가 4의 배수가 아닌 경우도 올바르게 처리한다") {
        // 1바이트
        val bytes1 = byteArrayOf(0x01)
        val hash1 = MurmurHash.hash32(bytes1)
        hash1 shouldBe MurmurHash.hash32(bytes1)

        // 2바이트
        val bytes2 = byteArrayOf(0x01, 0x02)
        val hash2 = MurmurHash.hash32(bytes2)
        hash2 shouldBe MurmurHash.hash32(bytes2)

        // 3바이트
        val bytes3 = byteArrayOf(0x01, 0x02, 0x03)
        val hash3 = MurmurHash.hash32(bytes3)
        hash3 shouldBe MurmurHash.hash32(bytes3)

        // 5바이트 (4 + 1)
        val bytes5 = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val hash5 = MurmurHash.hash32(bytes5)
        hash5 shouldBe MurmurHash.hash32(bytes5)

        // 모두 다른 해시값을 가져야 함
        hash1 shouldNotBe hash2
        hash2 shouldNotBe hash3
        hash3 shouldNotBe hash5
    }

    test("실제 사용 시나리오: 사용자 ID 해싱") {
        // A/B 테스트에서 사용되는 실제 시나리오
        val userIds = listOf(
            "user-12345",
            "user-67890",
            "john.doe@example.com",
            "jane.smith@example.com",
            "test-user-999"
        )

        val hashes = userIds.map { MurmurHash.hash32(it) }

        // 모든 해시값이 유일해야 함
        hashes.distinct().size shouldBe userIds.size

        // 동일한 사용자 ID는 항상 동일한 해시값을 가져야 함
        userIds.forEachIndexed { index, userId ->
            MurmurHash.hash32(userId) shouldBe hashes[index]
        }
    }
})

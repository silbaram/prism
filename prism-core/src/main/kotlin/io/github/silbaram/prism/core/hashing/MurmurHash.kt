package io.github.silbaram.prism.core.hashing

/**
 * MurmurHash3 32-bit 해시 알고리즘 구현체.
 *
 * MurmurHash3는 빠르고 균일한 분산 특성을 가진 비암호화 해시 함수입니다.
 * A/B 테스트의 사용자 분배, 일관된 해싱 등에 적합합니다.
 *
 * @see <a href="https://github.com/aappleby/smhasher">MurmurHash3 Reference Implementation</a>
 */
object MurmurHash {
    private const val DEFAULT_SEED = 0x9747b28c.toInt()
    private const val C1 = 0xcc9e2d51.toInt()
    private const val C2 = 0x1b873593.toInt()

    /**
     * 문자열에 대한 32-bit MurmurHash3 해시값을 계산합니다.
     *
     * @param key 해시할 문자열
     * @return 32-bit 해시값
     */
    fun hash32(key: String): Int {
        return hash32(key, DEFAULT_SEED)
    }

    /**
     * 문자열에 대한 32-bit MurmurHash3 해시값을 계산합니다.
     *
     * @param key 해시할 문자열
     * @param seed 해시 시드값 (다른 시드를 사용하면 다른 해시값 생성)
     * @return 32-bit 해시값
     */
    fun hash32(key: String, seed: Int): Int {
        return hash32(key.toByteArray(Charsets.UTF_8), seed)
    }

    /**
     * 바이트 배열에 대한 32-bit MurmurHash3 해시값을 계산합니다.
     *
     * @param data 해시할 바이트 배열
     * @return 32-bit 해시값
     */
    fun hash32(data: ByteArray): Int {
        return hash32(data, DEFAULT_SEED)
    }

    /**
     * 바이트 배열에 대한 32-bit MurmurHash3 해시값을 계산합니다.
     *
     * @param data 해시할 바이트 배열
     * @param seed 해시 시드값 (다른 시드를 사용하면 다른 해시값 생성)
     * @return 32-bit 해시값
     */
    fun hash32(data: ByteArray, seed: Int): Int {
        val length = data.size
        val nblocks = length / 4
        var h1 = seed

        // Body - 4바이트 블록 단위로 처리
        for (i in 0 until nblocks) {
            val index = i * 4
            var k1 = (data[index].toInt() and 0xff) or
                    ((data[index + 1].toInt() and 0xff) shl 8) or
                    ((data[index + 2].toInt() and 0xff) shl 16) or
                    ((data[index + 3].toInt() and 0xff) shl 24)

            k1 *= C1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= C2

            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        // Tail - 4바이트 단위로 나누어 떨어지지 않는 나머지 바이트 처리
        val tailIndex = nblocks * 4
        val remaining = length - tailIndex

        if (remaining > 0) {
            var k1 = when (remaining) {
                3 -> ((data[tailIndex + 2].toInt() and 0xff) shl 16) or
                     ((data[tailIndex + 1].toInt() and 0xff) shl 8) or
                     (data[tailIndex].toInt() and 0xff)
                2 -> ((data[tailIndex + 1].toInt() and 0xff) shl 8) or
                     (data[tailIndex].toInt() and 0xff)
                1 -> data[tailIndex].toInt() and 0xff
                else -> 0
            }

            k1 *= C1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= C2
            h1 = h1 xor k1
        }

        // Finalization - 최종 믹싱
        h1 = h1 xor length
        h1 = fmix32(h1)

        return h1
    }

    /**
     * 최종 믹싱 함수.
     * 해시의 균일한 분산을 보장하기 위한 비트 믹싱 과정입니다.
     */
    private fun fmix32(h: Int): Int {
        var h1 = h
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }
}

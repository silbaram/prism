package io.github.silbaram.prism.core.hashing

object MurmurHash {
    private const val SEED = 0x9747b28c.toInt()

    /**
     * MurmurHash3 32-bit implementation.
     */
    fun hash32(key: String): Int {
        val data = key.toByteArray(Charsets.UTF_8)
        val length = data.size
        val nblocks = length / 4
        var h1 = SEED

        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593.toInt()

        // Body
        for (i in 0 until nblocks) {
            val index = i * 4
            var k1 = (data[index].toInt() and 0xff) or
                    ((data[index + 1].toInt() and 0xff) shl 8) or
                    ((data[index + 2].toInt() and 0xff) shl 16) or
                    ((data[index + 3].toInt() and 0xff) shl 24)

            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2

            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        // Tail
        var k1 = 0
        val tailIndex = nblocks * 4
        val remaining = length - tailIndex

        if (remaining > 0) {
            if (remaining >= 3) k1 = k1 xor ((data[tailIndex + 2].toInt() and 0xff) shl 16)
            if (remaining >= 2) k1 = k1 xor ((data[tailIndex + 1].toInt() and 0xff) shl 8)
            if (remaining >= 1) k1 = k1 xor (data[tailIndex].toInt() and 0xff)
            
            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2
            h1 = h1 xor k1
        }

        // Finalization
        h1 = h1 xor length
        h1 = fmix32(h1)

        return h1
    }

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

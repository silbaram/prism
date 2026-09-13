package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.security.AdminSecurityConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdminCredentialValidationTest {
    @Test fun `missing credentials and plaintext passwords cannot configure an admin login`() {
        val security = AdminSecurityConfiguration()
        assertThrows(IllegalArgumentException::class.java) { security.users("", "", "", "") }
        assertThrows(IllegalArgumentException::class.java) { security.users("admin", "plaintext-password", "", "") }
    }

    @Test fun `malformed BCrypt costs and alphabet fail during configuration`() {
        val security = AdminSecurityConfiguration()
        val valid = "\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS"
        listOf(valid.replace("\$04\$", "\$00\$"), valid.replace("\$04\$", "\$32\$"),
            valid.dropLast(1) + "!").forEach { hash ->
            assertThrows(IllegalArgumentException::class.java) { security.users("admin", hash, "", "") }
            assertThrows(IllegalArgumentException::class.java) { security.users("admin", valid, "viewer", hash) }
        }
    }
}

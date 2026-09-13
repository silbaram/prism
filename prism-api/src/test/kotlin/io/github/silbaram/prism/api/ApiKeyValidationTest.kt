package io.github.silbaram.prism.api

import io.github.silbaram.prism.api.security.ApiSecurityConfiguration
import io.github.silbaram.prism.sdk.PrismClientOptions
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.config.annotation.web.builders.HttpSecurity

class ApiKeyValidationTest {
    @Test fun `API security cannot start with missing empty rotation or short keys`() {
        val security = ApiSecurityConfiguration()
        val http = mockk<HttpSecurity>()
        listOf("", "short", "valid-test-api-key-0123456789abcdef,").forEach { keys ->
            assertThrows(IllegalArgumentException::class.java) { security.apiSecurity(http, keys) }
        }
    }
    @Test fun `SDK keys are redacted and cannot inject additional headers`() {
        val key = "valid-test-api-key-0123456789abcdef"
        assertFalse(PrismClientOptions(apiKey = key).toString().contains(key))
        assertThrows(IllegalArgumentException::class.java) { PrismClientOptions(apiKey = "$key\r\nInjected: true") }
    }
}

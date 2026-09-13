package io.github.silbaram.prism.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurityConfiguration {
    @Bean fun apiUsers() = org.springframework.security.provisioning.InMemoryUserDetailsManager()
    @Bean fun apiSecurity(http: HttpSecurity, @Value("\${prism.api.keys:}") configuredKeys: String): SecurityFilterChain {
        val keys = configuredKeys.split(',').map(String::trim)
        require(keys.isNotEmpty() && keys.all { it.length in 32..512 && it.all { c -> c.code in 33..126 } }) {
            "Set PRISM_API_KEYS to comma-separated API keys of at least 32 characters"
        }
        val digests = keys.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)) }
        val filter = object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                val supplied = request.getHeader("X-Prism-Api-Key")
                if (supplied != null && supplied.length in 32..512 && request.getHeaders("X-Prism-Api-Key").toList().size == 1) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(supplied.toByteArray(Charsets.UTF_8))
                    if (digests.fold(false) { match, expected -> MessageDigest.isEqual(expected, digest) or match }) {
                        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                            "sdk", null, listOf(SimpleGrantedAuthority("ROLE_SDK")))
                    }
                }
                chain.doFilter(request, response)
            }
        }
        http.csrf { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .authorizeHttpRequests { it.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .requestMatchers("/error").permitAll().anyRequest().hasRole("SDK") }
            .exceptionHandling { it.authenticationEntryPoint { _, response, _ -> response.sendError(401) } }
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

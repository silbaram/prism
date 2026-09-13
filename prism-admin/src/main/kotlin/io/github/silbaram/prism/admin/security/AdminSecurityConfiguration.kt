package io.github.silbaram.prism.admin.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
class AdminSecurityConfiguration {
    @Bean fun passwordEncoder() = BCryptPasswordEncoder()

    @Bean fun users(
        @Value("\${prism.admin.username:}") username: String,
        @Value("\${prism.admin.password-hash:}") passwordHash: String,
        @Value("\${prism.admin.viewer-username:}") viewer: String,
        @Value("\${prism.admin.viewer-password-hash:}") viewerHash: String
    ): InMemoryUserDetailsManager {
        fun user(name: String, hash: String, role: String) = run {
            require(name.isNotBlank() && name.length <= 100 &&
                hash.matches(Regex("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}"))) {
                "Configure an admin username and BCrypt password hash via PRISM_ADMIN_USERNAME / PRISM_ADMIN_PASSWORD_HASH"
            }
            User.withUsername(name).password(hash).roles(role).build()
        }
        val users = mutableListOf(user(username, passwordHash, "ADMIN"))
        if (viewer.isNotEmpty() || viewerHash.isNotEmpty()) {
            require(viewer != username) { "Viewer username must differ from administrator" }
            users.add(user(viewer, viewerHash, "VIEWER"))
        }
        return InMemoryUserDetailsManager(users)
    }

    @Bean fun adminSecurity(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests {
            it.requestMatchers("/login", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/admin/experiments/new", "/admin/experiments/*/edit").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/**").hasAnyRole("ADMIN", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/admin/simulator/test").hasAnyRole("ADMIN", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/logout").authenticated()
                .anyRequest().hasRole("ADMIN")
        }.formLogin { it.defaultSuccessUrl("/admin/experiments", true).permitAll() }
            .logout { it.logoutSuccessUrl("/login?logout") }
        // Session authentication retains CSRF protection, including form writes and logout.
        return http.build()
    }
}

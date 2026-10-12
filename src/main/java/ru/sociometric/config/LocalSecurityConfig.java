package ru.sociometric.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Keeps the existing localhost and development workflow open by default. */
@Configuration
@ConditionalOnProperty(prefix = "sociometric.security", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalSecurityConfig {
    @Bean
    SecurityFilterChain localOpenSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}

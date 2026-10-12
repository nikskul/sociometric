package ru.sociometric.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** Optional HTTP Basic protection for a school instance exposed beyond localhost. */
@Configuration
@ConditionalOnProperty(prefix = "sociometric.security", name = "enabled", havingValue = "true")
public class SecurityConfig {

    @Bean
    PasswordEncoder schoolPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService schoolAdmin(org.springframework.core.env.Environment environment, PasswordEncoder encoder) {
        String username = environment.getProperty("sociometric.security.username");
        String password = environment.getProperty("sociometric.security.password");
        if (username == null || username.isBlank() || password == null || password.length() < 12) {
            throw new IllegalStateException("Задайте SOCIOMETRIC_ADMIN_USERNAME и пароль SOCIOMETRIC_ADMIN_PASSWORD длиной не менее 12 символов");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("ADMIN").build());
    }

    @Bean
    SecurityFilterChain schoolSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .build();
    }

}

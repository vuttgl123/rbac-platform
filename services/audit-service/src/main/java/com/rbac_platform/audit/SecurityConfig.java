package com.rbac_platform.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * No local users: stops Boot from creating its default "user" with a generated, logged password.
     * Until real authentication exists, nothing but /actuator/health is reachable.
     */
    @Bean
    InMemoryUserDetailsManager userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}

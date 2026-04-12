package com.ipogmp.tracker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration.
 *
 * Public routes:  /  /login  /api/ipos (GET)  /actuator/health  /ws/**
 * Protected:      /admin/**  POST|PUT|DELETE /api/ipos/**
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                // Disable CSRF only for REST API and WebSocket paths
                .ignoringRequestMatchers("/api/**", "/ws/**"))
            .authorizeHttpRequests(auth -> auth
                // ── Fully public — no login needed ────────────────────────
                .requestMatchers(
                    "/", "/login",
                    "/css/**", "/js/**", "/images/**", "/webjars/**",
                    "/actuator/health", "/actuator/info",
                    "/ws/**"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/ipos", "/api/ipos/**").permitAll()
                // ── Admin only ────────────────────────────────────────────
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,   "/api/ipos/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/api/ipos/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH,  "/api/ipos/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/ipos/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/admin", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/?logout")
                .permitAll()
            )
            .httpBasic(Customizer.withDefaults()); // For API clients

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails admin = User.builder()
                .username(adminUsername)
                .password(passwordEncoder().encode(adminPassword))
                .roles("ADMIN", "USER")
                .build();

        UserDetails viewer = User.builder()
                .username("viewer")
                .password(passwordEncoder().encode("viewer123"))
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(admin, viewer);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

package com.pg.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.pg.pg.PgRepository;
import com.pg.user.UserRepository;

@Configuration
public class SecurityConfig {

	private static final String[] STAFF = { "SUPER_ADMIN", "OWNER", "MANAGER" };

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, UserRepository users,
			PgRepository pgs) throws Exception {
		http.csrf(csrf -> csrf.disable()).cors(Customizer -> { })
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
					res.setStatus(401);
					res.setContentType(MediaType.APPLICATION_JSON_VALUE);
					res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
				}).accessDeniedHandler((req, res, ex) -> {
					res.setStatus(403);
					res.setContentType(MediaType.APPLICATION_JSON_VALUE);
					res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}");
				}))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						// Public: login, registration, refresh, and public PG & room exploration.
						.requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh").permitAll()
						.requestMatchers("/api/public/**").permitAll()
						.requestMatchers("/api/auth/**").authenticated()
						.requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
						.requestMatchers("/api/users/**").hasAnyRole("SUPER_ADMIN", "OWNER")
						.requestMatchers("/api/me/**").hasRole("RESIDENT")
						// Residents may explore PGs and look at room availability.
						.requestMatchers(HttpMethod.GET, "/api/rooms", "/api/rooms/**", "/api/explore/**")
						.hasAnyRole("SUPER_ADMIN", "OWNER", "MANAGER", "RESIDENT")
						.requestMatchers("/api/rooms/**", "/api/residents/**", "/api/payments/**",
								"/api/expenses/**", "/api/master/**", "/api/dashboard/**",
								"/api/notification-config/**", "/api/notifications/**",
								"/api/booking-requests/**", "/api/pg/**")
						.hasAnyRole(STAFF)
						.anyRequest().denyAll())
				.addFilterBefore(new JwtFilter(jwtService, users, pgs), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.origins}") String origins) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}

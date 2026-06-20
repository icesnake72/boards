package com.sbs.board.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 평문을 암호하는 객체, 암호화만 가능
        return new BCryptPasswordEncoder();
    }
    // "abc" - 암호화 알고리즘, 비밀번호(key) -> "00112233"

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)      // CSRF 비활성화
            .formLogin(AbstractHttpConfigurer::disable) // Spring Security Form Login 기능 사용하지 않음
            .httpBasic(AbstractHttpConfigurer::disable) // Spring Security Form Login 기능 사용하지 않음
                // 모든 요청을 무조건 허용한다
            .authorizeHttpRequests(auth-> auth.anyRequest().permitAll());

        return http.build();
    }
}

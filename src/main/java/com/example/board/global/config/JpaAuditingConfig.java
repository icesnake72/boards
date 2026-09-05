package com.example.board.global.config;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

  // 단계 16: Auditing 시각을 DB 저장 정밀도(마이크로초)로 절단한다.
  // Linux JDK의 LocalDateTime.now()는 나노초까지 주지만 DATETIME(6)은 마이크로초라,
  // 절단 없이는 "메모리 엔티티의 createdAt ≠ DB에 저장된 createdAt"가 된다.
  // keyset 커서처럼 이 값을 되돌려 받아 등호 비교하는 코드가 어긋나(커서 행 중복 반환)
  // CI(Linux)에서만 실패하는 테스트로 드러났다 — 값의 정본을 저장 정밀도에 맞춘다.
  @Bean
  public DateTimeProvider auditingDateTimeProvider() {
    return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
  }
}

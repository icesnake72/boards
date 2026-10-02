package com.example.board.live;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

// 단계 18: 참여 코드 — 사람이 화면을 보고 받아 적는 값이라 헷갈리는 0/O, 1/I를 뺀다.
// SecureRandom: 코드를 추측해 남의 이벤트에 들어오는 것을 어렵게 한다(Random은 예측 가능).
@Component
public class LiveCodeGenerator {

  static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  static final int LENGTH = 6;

  private final SecureRandom random = new SecureRandom();

  public String generate() {
    StringBuilder code = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  public static String normalize(String code) {
    return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
  }
}

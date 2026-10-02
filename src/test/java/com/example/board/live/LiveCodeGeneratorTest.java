package com.example.board.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class LiveCodeGeneratorTest {

  private final LiveCodeGenerator generator = new LiveCodeGenerator();

  @RepeatedTest(200)
  void should_generateSixCharsWithoutConfusingLetters() {
    assertThat(generator.generate()).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}");
  }

  @Test
  void should_normalizeToTrimmedUpperCase() {
    assertThat(LiveCodeGenerator.normalize(" k7m2qx ")).isEqualTo("K7M2QX");
  }
}

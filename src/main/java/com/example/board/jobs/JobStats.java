package com.example.board.jobs;

import java.util.List;

public record JobStats(long streamLength, long pending, List<ConsumerStat> consumers, long deadLetters) {

  public record ConsumerStat(String name, long pending, long idleMs) {
  }
}

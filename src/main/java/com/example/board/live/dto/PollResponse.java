package com.example.board.live.dto;

import com.example.board.live.LiveStatus;
import com.example.board.live.Poll;
import java.util.List;
import java.util.Map;

// myOptionId: 조회자의 선택(없으면 null). SSE 브로드캐스트에는 항상 null로 싣는다.
public record PollResponse(
    Long id,
    String title,
    LiveStatus status,
    List<PollOptionResponse> options,
    long totalVotes,
    Long myOptionId
) {

  public static PollResponse of(Poll poll, Map<Long, Long> counts, Long myOptionId) {
    List<PollOptionResponse> options = poll.getOptions().stream()
        .map(option -> new PollOptionResponse(
            option.getId(), option.getText(), counts.getOrDefault(option.getId(), 0L)))
        .toList();
    long total = options.stream().mapToLong(PollOptionResponse::count).sum();
    return new PollResponse(poll.getId(), poll.getTitle(), poll.getStatus(), options, total,
        myOptionId);
  }
}

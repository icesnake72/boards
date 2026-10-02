package com.example.board.live.dto;

import java.util.List;

// 단계 18: 입장·재연결 때 한 번에 받는 "현재 상태 전체". 이후 변화는 SSE로만 받는다.
public record LiveSnapshotResponse(LiveEventResponse event, List<QuestionResponse> questions) {
}

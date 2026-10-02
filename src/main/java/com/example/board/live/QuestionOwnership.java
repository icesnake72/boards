package com.example.board.live;

// 단계 18: 질문 삭제 인가에 필요한 두 id만 담는 JPQL 생성자 투영.
public record QuestionOwnership(Long authorId, Long hostId) {

  public boolean allows(Long userId) {
    return authorId.equals(userId) || hostId.equals(userId);
  }
}

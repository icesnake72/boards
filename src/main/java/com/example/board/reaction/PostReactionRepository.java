package com.example.board.reaction;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostReactionRepository extends JpaRepository<PostReaction, Long> {

  // 토글/전환 판단용 — 이 사용자가 이 글에 이미 남긴 반응(있으면 갱신/삭제, 없으면 새로 생성).
  Optional<PostReaction> findByPostIdAndUserId(Long postId, Long userId);

  // 단건 게시글의 LIKE/DISLIKE 개수(단건 상세 응답용).
  long countByPostIdAndType(Long postId, ReactionType type);
}

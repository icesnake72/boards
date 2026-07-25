package com.example.board.comment.dto;

import com.example.board.comment.Comment;
import com.example.board.reaction.CommentReactionSummary;
import com.example.board.reaction.ReactionType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record CommentResponse(
    Long id,
    String authorUsername,
    String content,
    boolean deleted,
    long likeCount,
    long dislikeCount,
    ReactionType myReaction,
    LocalDateTime createdAt,
    List<CommentResponse> children
) {

  // soft delete된 댓글의 실제 내용은 노출하지 않도록 마스킹한다(트리 유지 목적상 행은 남기되 내용만 감춤).
  private static final String DELETED_CONTENT = "삭제된 댓글입니다";

  // 단계 13: 반응 요약은 서비스가 목록 전체를 집계(N+1 회피)해 commentId→summary 맵으로 주입한다.
  // children(대댓글)도 같은 맵에서 자신의 반응을 찾아 재귀적으로 채운다.
  // 주의: comment.getChildren()은 LAZY 컬렉션이므로 트랜잭션 내(@BatchSize 로딩 가능한 상태)에서 호출해야 한다.
  // 1단계 정책상 대댓글(children)의 children은 항상 비어 있다.
  public static CommentResponse from(Comment comment, Map<Long, CommentReactionSummary> reactions) {
    // 대댓글(비-root)의 children은 1단계 정책상 항상 비어 있으므로 접근조차 하지 않는다
    // (불필요한 빈 컬렉션 배치 로딩 방지). 최상위 댓글만 children을 매핑한다.
    List<CommentResponse> children = comment.isRoot()
        ? comment.getChildren().stream().map(child -> from(child, reactions)).toList()
        : List.of();
    CommentReactionSummary summary =
        reactions.getOrDefault(comment.getId(), CommentReactionSummary.empty());
    return new CommentResponse(
        comment.getId(),
        comment.getAuthor().getUsername(),
        comment.isDeleted() ? DELETED_CONTENT : comment.getContent(),
        comment.isDeleted(),
        summary.likeCount(),
        summary.dislikeCount(),
        summary.myReaction(),
        comment.getCreatedAt(),
        children);
  }
}

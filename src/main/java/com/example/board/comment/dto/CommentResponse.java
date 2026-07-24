package com.example.board.comment.dto;

import com.example.board.comment.Comment;
import java.time.LocalDateTime;
import java.util.List;

public record CommentResponse(
    Long id,
    String authorUsername,
    String content,
    boolean deleted,
    LocalDateTime createdAt,
    List<CommentResponse> children
) {

  // soft delete된 댓글의 실제 내용은 노출하지 않도록 마스킹한다(트리 유지 목적상 행은 남기되 내용만 감춤).
  private static final String DELETED_CONTENT = "삭제된 댓글입니다";

  // 주의: comment.getChildren()은 LAZY 컬렉션이므로 트랜잭션 내(@BatchSize 로딩 가능한 상태)에서 호출해야 한다.
  // 서비스가 트랜잭션 경계 안에서 map(CommentResponse::from) 하므로 안전하다.
  // 1단계 정책상 대댓글(children)의 children은 항상 비어 있다.
  public static CommentResponse from(Comment comment) {
    // 대댓글(비-root)의 children은 1단계 정책상 항상 비어 있으므로 접근조차 하지 않는다
    // (불필요한 빈 컬렉션 배치 로딩 방지). 최상위 댓글만 children을 매핑한다.
    List<CommentResponse> children = comment.isRoot()
        ? comment.getChildren().stream().map(CommentResponse::from).toList()
        : List.of();
    return new CommentResponse(
        comment.getId(),
        comment.getAuthor().getUsername(),
        comment.isDeleted() ? DELETED_CONTENT : comment.getContent(),
        comment.isDeleted(),
        comment.getCreatedAt(),
        children);
  }
}

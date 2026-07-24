package com.example.board.notification;

// 댓글 작성 사실만 담는 이벤트. 엔티티 참조 대신 id만 담는다 —
// 리스너는 AFTER_COMMIT(원 트랜잭션 종료 후 새 트랜잭션)에서 실행되므로 detach된 프록시를 피한다.
// recipient(작성자) 결정은 리스너가 postId/parentCommentId로 재조회한다(발행자는 알림 정책을 모른다).
public record CommentCreatedEvent(
    Long commentId,
    Long postId,
    Long parentCommentId,  // null이면 최상위 댓글(→ COMMENT_ON_POST)
    Long actorId           // 댓글을 작성한 사용자 = 알림의 actor
) {}

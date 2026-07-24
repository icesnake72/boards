package com.example.board.notification;

public enum NotificationType {
  // 게시글에 댓글이 달림 → 게시글 작성자에게
  COMMENT_ON_POST,
  // 댓글에 대댓글이 달림 → 원댓글 작성자에게
  REPLY_ON_COMMENT
}

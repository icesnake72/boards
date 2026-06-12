package com.example.board.post.dto;

import com.example.board.post.Post;
import java.time.LocalDateTime;

public record PostResponse(
    Long id,
    Long boardId,
    String boardName,
    String authorUsername,
    String title,
    String content,
    int viewCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

  public static PostResponse from(Post post) {
    return new PostResponse(
        post.getId(),
        post.getBoard().getId(),
        post.getBoard().getName(),
        post.getAuthor().getUsername(),
        post.getTitle(),
        post.getContent(),
        post.getViewCount(),
        post.getCreatedAt(),
        post.getUpdatedAt());
  }
}

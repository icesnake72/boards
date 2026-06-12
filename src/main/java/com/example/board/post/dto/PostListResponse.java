package com.example.board.post.dto;

import com.example.board.post.Post;
import java.time.LocalDateTime;

public record PostListResponse(
    Long id,
    String title,
    String authorUsername,
    int viewCount,
    LocalDateTime createdAt
) {

  public static PostListResponse from(Post post) {
    return new PostListResponse(
        post.getId(),
        post.getTitle(),
        post.getAuthor().getUsername(),
        post.getViewCount(),
        post.getCreatedAt());
  }
}

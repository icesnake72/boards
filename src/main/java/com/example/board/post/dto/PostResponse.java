package com.example.board.post.dto;

import com.example.board.post.Post;
import java.time.LocalDateTime;
import java.util.List;

public record PostResponse(
    Long id,
    Long boardId,
    String boardName,
    String authorUsername,
    String title,
    String content,
    int viewCount,
    List<PostImageResponse> images,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

  // 주의: post.getImages()는 LAZY 컬렉션이므로 트랜잭션 내(fetch join 또는 영속 상태)에서 호출해야 한다.
  // 단건 조회는 PostRepository.findDetailById가 images를 fetch join 한다.
  public static PostResponse from(Post post) {
    List<PostImageResponse> images = post.getImages().stream()
        .map(PostImageResponse::from)
        .toList();
    return new PostResponse(
        post.getId(),
        post.getBoard().getId(),
        post.getBoard().getName(),
        post.getAuthor().getUsername(),
        post.getTitle(),
        post.getContent(),
        post.getViewCount(),
        images,
        post.getCreatedAt(),
        post.getUpdatedAt());
  }
}

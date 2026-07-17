package com.example.board.post.dto;

import com.example.board.post.Post;
import com.example.board.post.PostImage;
import java.time.LocalDateTime;

public record PostListResponse(
    Long id,
    String title,
    String authorUsername,
    int viewCount,
    String thumbnailUrl,
    LocalDateTime createdAt
) {

  // thumbnailUrl은 첫 이미지의 url(없으면 null). getImages()는 @BatchSize로 로딩되는 LAZY 컬렉션이라
  // @Transactional(readOnly=true) 매핑 시점에 안전하게 접근된다.
  public static PostListResponse from(Post post) {
    String thumbnailUrl = post.getImages().stream()
        .findFirst()
        .map(PostImage::getStoredName)
        .map(name -> PostImageResponse.URL_PREFIX + name)
        .orElse(null);
    return new PostListResponse(
        post.getId(),
        post.getTitle(),
        post.getAuthor().getUsername(),
        post.getViewCount(),
        thumbnailUrl,
        post.getCreatedAt());
  }
}

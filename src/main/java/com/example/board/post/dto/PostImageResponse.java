package com.example.board.post.dto;

import com.example.board.post.PostImage;

// 단계 10: 이미지 응답. url은 정적 서빙 경로(/images/{storedName})로 조립한다.
public record PostImageResponse(
    Long id,
    String url,
    String originalName,
    int sortOrder
) {

  public static final String URL_PREFIX = "/images/";

  public static PostImageResponse from(PostImage image) {
    return new PostImageResponse(
        image.getId(),
        URL_PREFIX + image.getStoredName(),
        image.getOriginalName(),
        image.getSortOrder());
  }
}

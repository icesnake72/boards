package com.example.board.post.dto;

import com.example.board.post.Post;
import com.example.board.reaction.ReactionType;
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
    long likeCount,
    long dislikeCount,
    ReactionType myReaction,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

  // 단계 13: 반응(likeCount/dislikeCount/myReaction)은 from(Post)만으로는 채울 수 없어(별도 테이블)
  // 서비스가 반응 요약을 조회해 넘긴다. 방금 만든/수정한 글은 반응이 없으므로 0/0/null을 전달하면 된다.
  // 주의: post.getImages()는 LAZY 컬렉션이므로 트랜잭션 내(fetch join 또는 영속 상태)에서 호출해야 한다.
  public static PostResponse from(
      Post post, long likeCount, long dislikeCount, ReactionType myReaction) {
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
        likeCount,
        dislikeCount,
        myReaction,
        post.getCreatedAt(),
        post.getUpdatedAt());
  }
}

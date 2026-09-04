package com.example.board.post.dto;

import com.example.board.post.Post;
import java.time.LocalDateTime;
import java.util.List;

// 단계 16: keyset(cursor) 페이지네이션 응답.
// Page<T>와 달리 전체 건수(totalElements)를 세지 않는다 — COUNT(*) 자체가 대량
// 테이블에서 비싼 쿼리라서, 무한스크롤에는 "다음이 있는가"만 알려주면 충분하다.
// lastCreatedAt/lastId가 다음 요청의 커서다(클라이언트는 이 값을 그대로 되돌려 보낸다).
public record PostCursorResponse(
    List<PostListResponse> items,
    boolean hasNext,
    LocalDateTime lastCreatedAt,
    Long lastId
) {

  // rows는 size+1건까지 조회된 상태로 들어온다 — 여분 1건의 존재가 hasNext의 근거.
  public static PostCursorResponse of(List<Post> rows, int size) {
    boolean hasNext = rows.size() > size;
    List<Post> pageRows = hasNext ? rows.subList(0, size) : rows;
    List<PostListResponse> items = pageRows.stream().map(PostListResponse::from).toList();
    Post last = pageRows.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
    return new PostCursorResponse(
        items,
        hasNext,
        last == null ? null : last.getCreatedAt(),
        last == null ? null : last.getId());
  }
}

package com.example.board.post;

import com.example.board.auth.LoginUserId;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PostController {

  private final PostService postService;

  @GetMapping("/boards/{boardId}/posts")
  public Page<PostListResponse> getPosts(
      @PathVariable Long boardId,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    return postService.getPosts(boardId, pageable);
  }

  // @LoginUserId가 로그인 사용자 id 주입과 비로그인 401 처리를 담당한다.
  @PostMapping("/boards/{boardId}/posts")
  @ResponseStatus(HttpStatus.CREATED)
  public PostResponse create(
      @PathVariable Long boardId,
      @LoginUserId Long userId,
      @Valid @RequestBody PostCreateRequest request) {
    return postService.create(boardId, userId, request);
  }

  @GetMapping("/posts/{id}")
  public PostResponse getPost(@PathVariable Long id) {
    return postService.getPost(id);
  }

  @PutMapping("/posts/{id}")
  public PostResponse update(
      @PathVariable Long id,
      @LoginUserId Long userId,
      @Valid @RequestBody PostUpdateRequest request) {
    return postService.update(id, userId, request);
  }

  @DeleteMapping("/posts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable Long id,
      @LoginUserId Long userId) {
    postService.delete(id, userId);
  }
}

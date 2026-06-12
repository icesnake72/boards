package com.example.board.post;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.ForbiddenException;
import com.example.board.global.exception.NotFoundException;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostService {

  private final PostRepository postRepository;
  private final BoardRepository boardRepository;
  private final UserRepository userRepository;

  @Transactional(readOnly = true)
  public Page<PostListResponse> getPosts(Long boardId, Pageable pageable) {
    if (!boardRepository.existsById(boardId)) {
      throw new NotFoundException(ErrorCode.BOARD_NOT_FOUND);
    }
    return postRepository.findByBoardId(boardId, pageable).map(PostListResponse::from);
  }

  @Transactional
  public PostResponse create(Long boardId, Long loginUserId, PostCreateRequest request) {
    Board board = boardRepository.findById(boardId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.BOARD_NOT_FOUND));
    User author = userRepository.findById(loginUserId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

    Post post = postRepository.save(new Post(board, author, request.title(), request.content()));
    return PostResponse.from(post);
  }

  @Transactional
  public PostResponse getPost(Long id) {
    Post post = findPost(id);
    post.increaseViewCount(); // dirty checking으로 트랜잭션 커밋 시 UPDATE 실행
    return PostResponse.from(post);
  }

  @Transactional
  public PostResponse update(Long id, Long loginUserId, PostUpdateRequest request) {
    Post post = findPost(id);
    validateAuthor(post, loginUserId);
    post.update(request.title(), request.content());
    return PostResponse.from(post);
  }

  @Transactional
  public void delete(Long id, Long loginUserId) {
    Post post = findPost(id);
    validateAuthor(post, loginUserId);
    postRepository.delete(post);
  }

  private Post findPost(Long id) {
    return postRepository.findDetailById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POST_NOT_FOUND));
  }

  private void validateAuthor(Post post, Long userId) {
    if (!post.isAuthor(userId)) {
      throw new ForbiddenException(ErrorCode.POST_ACCESS_DENIED);
    }
  }
}

package com.example.board.post;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.global.storage.FileStorageService;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class PostService {

  private final PostRepository postRepository;
  private final BoardRepository boardRepository;
  private final UserRepository userRepository;
  private final FileStorageService fileStorageService;

  @Transactional(readOnly = true)
  public Page<PostListResponse> getPosts(Long boardId, Pageable pageable) {
    if (!boardRepository.existsById(boardId)) {
      throw new NotFoundException(ErrorCode.BOARD_NOT_FOUND);
    }
    return postRepository.findByBoardId(boardId, pageable).map(PostListResponse::from);
  }

  // 단계 10 처리에 의해 변경 — 기존 시그니처 create(boardId, loginUserId, request)에 images 파라미터 추가.
  // 파일 저장(디스크) → DB save 순서로 처리하고, DB 실패 시 이미 저장된 파일을 best-effort 삭제해
  // 디스크/DB 정합성을 맞춘다(고아 파일 방지).
  @Transactional
  public PostResponse create(
      Long boardId, Long loginUserId, PostCreateRequest request, List<MultipartFile> images) {
    Board board = boardRepository.findById(boardId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.BOARD_NOT_FOUND));
    User author = userRepository.findById(loginUserId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

    List<String> storedNames = new ArrayList<>();
    try {
      Post post = new Post(board, author, request.title(), request.content());
      if (images != null) {
        int order = 0;
        for (MultipartFile file : images) {
          String storedName = fileStorageService.store(file);
          storedNames.add(storedName);
          post.addImage(new PostImage(
              storedName, file.getOriginalFilename(), file.getContentType(), file.getSize(),
              order++));
        }
      }
      Post saved = postRepository.save(post);
      return PostResponse.from(saved);
    } catch (RuntimeException e) {
      storedNames.forEach(fileStorageService::delete);
      throw e;
    }
  }

  @Transactional
  public PostResponse getPost(Long id) {
    Post post = findPost(id);
    post.increaseViewCount(); // dirty checking으로 트랜잭션 커밋 시 UPDATE 실행
    return PostResponse.from(post);
  }

  // 단계 6: 작성자 검사(권한)는 컨트롤러의 @PreAuthorize(@postSecurity)로 이동.
  // 서비스는 비즈니스 로직(조회/수정)만 담당한다.
  @Transactional
  public PostResponse update(Long id, PostUpdateRequest request) {
    Post post = findPost(id);
    post.update(request.title(), request.content());
    return PostResponse.from(post);
  }

  // 단계 10: 게시글 삭제 시 자식 PostImage 행은 cascade/orphanRemoval로 함께 지워지지만,
  // 디스크 파일은 JPA가 모르므로 별도로 정리한다.
  // 파일 삭제를 트랜잭션 안에서 하면, 커밋이 실패해 롤백될 때 파일은 이미 지워졌는데 DB row는
  // 남아 "깨진 이미지 링크"가 된다. 그래서 커밋이 확정된 뒤(afterCommit)에만 파일을 지운다.
  @Transactional
  public void delete(Long id) {
    Post post = findPost(id);
    List<String> storedNames = post.getImages().stream().map(PostImage::getStoredName).toList();
    postRepository.delete(post);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        storedNames.forEach(fileStorageService::delete);
      }
    });
  }

  private Post findPost(Long id) {
    return postRepository.findDetailById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POST_NOT_FOUND));
  }
}

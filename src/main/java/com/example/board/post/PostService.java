package com.example.board.post;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.global.exception.BusinessException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

  // 게시글당 첨부 가능한 최대 이미지 장수(PostController.MAX_IMAGE_COUNT와 동일 정책).
  // 수정 시 최종 개수(현재 - 삭제 + 신규)는 서비스만 알 수 있어 여기서 검증한다.
  private static final int MAX_IMAGE_COUNT = 5;

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

  // 단계 10: 조회수는 "남이 볼 때"만 올린다 — 본인 글 조회는 자기 조회수를 부풀리지 않도록 제외.
  // viewerId는 비로그인이면 null(GET /posts/{id}는 permitAll). isAuthor(null)은 false이므로
  // 비로그인 조회는 자연히 "남"으로 취급되어 증가한다.
  @Transactional
  public PostResponse getPost(Long id, Long viewerId) {
    Post post = findPost(id);
    if (!post.isAuthor(viewerId)) {
      post.increaseViewCount(); // dirty checking으로 트랜잭션 커밋 시 UPDATE 실행
    }
    return PostResponse.from(post);
  }

  // 단계 6: 작성자 검사(권한)는 컨트롤러의 @PreAuthorize(@postSecurity)로 이동.
  // 단계 10 처리에 의해 변경 — 이미지 개별 삭제(deleteImageIds) + 신규 추가(images)를 지원한다.
  // 정합성 원칙:
  //  - 장수 검증은 파일을 디스크에 저장하기 전에 수행한다(초과인데 저장하면 고아 파일 발생).
  //  - 신규 파일 저장 중 실패하면 이번에 저장한 신규 파일만 best-effort 삭제한다.
  //    (아직 커밋 전이라 삭제 예정인 기존 파일은 지우면 안 된다.)
  //  - 삭제된 이미지의 물리 파일은 커밋 확정 후(afterCommit)에만 지운다(롤백 시 파일 선삭제 방지).
  @Transactional
  public PostResponse update(Long id, PostUpdateRequest request, List<MultipartFile> images) {
    Post post = findPost(id);

    Set<Long> deleteIds = request.deleteImageIds() == null
        ? Set.of()
        : new HashSet<>(request.deleteImageIds());
    int addCount = images == null ? 0 : images.size();
    int deletableCount = countDeletable(post, deleteIds);
    int finalCount = post.getImages().size() - deletableCount + addCount;
    if (finalCount > MAX_IMAGE_COUNT) {
      throw new BusinessException(ErrorCode.FILE_COUNT_EXCEEDED);
    }

    post.update(request.title(), request.content());

    // 남의 글/존재하지 않는 이미지 id는 removeImagesByIds에서 조용히 무시된다.
    List<String> removedStoredNames = post.removeImagesByIds(deleteIds);

    List<String> newStoredNames = new ArrayList<>();
    try {
      if (images != null && !images.isEmpty()) {
        int order = nextSortOrder(post);
        for (MultipartFile file : images) {
          String storedName = fileStorageService.store(file);
          newStoredNames.add(storedName);
          post.addImage(new PostImage(
              storedName, file.getOriginalFilename(), file.getContentType(), file.getSize(),
              order++));
        }
      }
    } catch (RuntimeException e) {
      newStoredNames.forEach(fileStorageService::delete);
      throw e;
    }

    if (!removedStoredNames.isEmpty()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          removedStoredNames.forEach(fileStorageService::delete);
        }
      });
    }

    return PostResponse.from(post);
  }

  private int countDeletable(Post post, Set<Long> deleteIds) {
    if (deleteIds.isEmpty()) {
      return 0;
    }
    return (int) post.getImages().stream()
        .filter(image -> deleteIds.contains(image.getId()))
        .count();
  }

  private int nextSortOrder(Post post) {
    return post.getImages().stream().mapToInt(PostImage::getSortOrder).max().orElse(-1) + 1;
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

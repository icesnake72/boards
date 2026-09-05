package com.example.board.post;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.global.storage.FileStorageService;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostCursorResponse;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.reaction.PostReactionSummary;
import com.example.board.reaction.ReactionService;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
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
  private final ReactionService reactionService;

  // 단계 16 사후 개선(지연 조인) — LAB §6-1: 조인을 끌고 OFFSET을 지나가면 deep page
  // 에서 5,126ms(실측). ① id만 covering index로 페이징하고 ② 조인 로딩은 확정된
  // 페이지의 행에만 수행한다(실측 66ms, 78배). IN 결과는 순서가 없으므로 Map으로
  // 받아 id 페이지의 순서를 복원한다.
  @Transactional(readOnly = true)
  public Page<PostListResponse> getPosts(Long boardId, Pageable pageable) {
    if (!boardRepository.existsById(boardId)) {
      throw new NotFoundException(ErrorCode.BOARD_NOT_FOUND);
    }
    Page<Long> idPage = postRepository.findIdsByBoardId(boardId, pageable);
    Map<Long, Post> postsById = idPage.hasContent()
        ? postRepository.findWithBoardAndAuthorByIdIn(idPage.getContent()).stream()
            .collect(Collectors.toMap(Post::getId, Function.identity()))
        : Map.of();
    return idPage.map(id -> PostListResponse.from(postsById.get(id)));
  }

  // 단계 17: BOOLEAN MODE의 검색 문법 문자들. 사용자는 "포함 검색"을 원하는 것이지
  // 검색 연산자를 쓰는 게 아니므로 전부 데이터가 아닌 잡음으로 취급해 제거한다
  // — 미검증 입력이 그대로 against()에 들어가면 문법 오류(500)나 의도치 않은
  // 제외 검색(-단어)이 된다. LAB의 "죽일 수 없는 쿼리" 방어선이기도 하다.
  private static final Pattern BOOLEAN_SYNTAX = Pattern.compile("[+\\-><()~*\"@]");

  // 단계 17: 게시글 검색 — FULLTEXT(ngram) + keyset. 응답·커서 계약은
  // getPostsByCursor와 동일해서 프론트 무한스크롤 코드가 그대로 재사용된다.
  @Transactional(readOnly = true)
  public PostCursorResponse searchPosts(
      Long boardId, String query, LocalDateTime lastCreatedAt, Long lastId, int size) {
    if (!boardRepository.existsById(boardId)) {
      throw new NotFoundException(ErrorCode.BOARD_NOT_FOUND);
    }
    String booleanQuery = toBooleanQuery(query);
    int limit = size + 1;
    List<Long> ids = (lastCreatedAt == null || lastId == null)
        ? postRepository.searchIdsByBoardId(boardId, booleanQuery, limit)
        : postRepository.searchIdsByBoardIdAfterCursor(
            boardId, booleanQuery, lastCreatedAt, lastId, limit);
    if (ids.isEmpty()) {
      return PostCursorResponse.of(List.of(), size);
    }
    // 지연 조인 2단계 + 순서 복원 — id 목록(size+1 포함)을 그대로 엔티티로 바꿔
    // PostCursorResponse.of에 넘기면 hasNext 판정과 트리밍까지 기존 로직이 처리한다.
    Map<Long, Post> postsById = postRepository.findWithBoardAndAuthorByIdIn(ids).stream()
        .collect(Collectors.toMap(Post::getId, Function.identity()));
    List<Post> ordered = ids.stream().map(postsById::get).toList();
    return PostCursorResponse.of(ordered, size);
  }

  // 검색어 정제 3단계: ① 연산자 제거 ② 2글자 미만 토큰 제외(ngram_token_size=2
  // 미만은 색인에 없어, AND에 끼면 전체를 0건으로 만든다) ③ 남은 토큰마다 +를 붙여
  // "모두 포함(AND)" 의미로 통일 — 연산자 없는 BOOLEAN MODE는 토큰이 선택 사항이라
  // OR처럼 동작해 버리기 때문. 살아남은 토큰이 없으면 명시적으로 거부한다(400).
  private String toBooleanQuery(String query) {
    String cleaned = query == null ? "" : BOOLEAN_SYNTAX.matcher(query).replaceAll(" ");
    List<String> tokens = Arrays.stream(cleaned.trim().split("\\s+"))
        .filter(token -> token.length() >= 2)
        .toList();
    if (tokens.isEmpty()) {
      throw new BusinessException(ErrorCode.SEARCH_QUERY_TOO_SHORT);
    }
    return tokens.stream().map(token -> "+" + token).collect(Collectors.joining(" "));
  }

  // 단계 16: keyset(cursor) 목록 조회 — 무한스크롤용.
  // 커서(lastCreatedAt, lastId)가 없으면 첫 페이지, 있으면 그 지점 이후를 잇는다.
  // size+1건을 조회해 여분 1건의 존재로 hasNext를 판정한다(COUNT 쿼리 없이).
  @Transactional(readOnly = true)
  public PostCursorResponse getPostsByCursor(
      Long boardId, LocalDateTime lastCreatedAt, Long lastId, int size) {
    if (!boardRepository.existsById(boardId)) {
      throw new NotFoundException(ErrorCode.BOARD_NOT_FOUND);
    }
    Limit limit = Limit.of(size + 1);
    List<Post> rows = (lastCreatedAt == null || lastId == null)
        ? postRepository.findByBoardIdOrderByCreatedAtDescIdDesc(boardId, limit)
        : postRepository.findSliceByBoardIdAfterCursor(boardId, lastCreatedAt, lastId, limit);
    return PostCursorResponse.of(rows, size);
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
      // 방금 만든 글은 반응이 없으므로 0/0/null.
      return PostResponse.from(saved, 0, 0, null);
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
    PostReactionSummary reaction = reactionService.getPostReaction(id, viewerId);
    return PostResponse.from(
        post, reaction.likeCount(), reaction.dislikeCount(), reaction.myReaction());
  }

  // 단계 6: 작성자 검사(권한)는 컨트롤러의 @PreAuthorize(@postSecurity)로 이동.
  // 단계 10 처리에 의해 변경 — 이미지 개별 삭제(deleteImageIds) + 신규 추가(images)를 지원한다.
  // 정합성 원칙:
  //  - 장수 검증은 파일을 디스크에 저장하기 전에 수행한다(초과인데 저장하면 고아 파일 발생).
  //  - 신규 파일 저장 중 실패하면 이번에 저장한 신규 파일만 best-effort 삭제한다.
  //    (아직 커밋 전이라 삭제 예정인 기존 파일은 지우면 안 된다.)
  //  - 삭제된 이미지의 물리 파일은 커밋 확정 후(afterCommit)에만 지운다(롤백 시 파일 선삭제 방지).
  @Transactional
  public PostResponse update(
      Long id, Long viewerId, PostUpdateRequest request, List<MultipartFile> images) {
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

    // 수정 시점의 반응 현황을 함께 반환(수정으로 반응이 바뀌진 않지만 응답 일관성 유지).
    // viewerId(=작성자)를 넘겨 myReaction까지 정확히 채운다.
    PostReactionSummary reaction = reactionService.getPostReaction(id, viewerId);
    return PostResponse.from(
        post, reaction.likeCount(), reaction.dislikeCount(), reaction.myReaction());
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

package com.example.board.comment;

import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.comment.dto.CommentUpdateRequest;
import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.notification.CommentCreatedEvent;
import com.example.board.post.Post;
import com.example.board.post.PostRepository;
import com.example.board.reaction.CommentReactionSummary;
import com.example.board.reaction.ReactionService;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommentService {

  private final CommentRepository commentRepository;
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final ReactionService reactionService;

  @Transactional
  public CommentResponse create(Long postId, Long loginUserId, CommentCreateRequest request) {
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POST_NOT_FOUND));
    User author = userRepository.findById(loginUserId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

    Comment comment = new Comment(post, author, request.content());
    if (request.parentId() != null) {
      Comment parent = findComment(request.parentId());
      validateReplyTarget(parent, postId);
      parent.addReply(comment);
    }

    Comment saved = commentRepository.save(comment);

    // 알림 도메인을 몰라도 됨 — 이벤트만 던진다. 대상 결정/자기 자신 스킵은 리스너의 책임.
    // AFTER_COMMIT 리스너라 이 트랜잭션이 커밋된 뒤에만 처리된다(롤백되면 알림도 안 나감).
    eventPublisher.publishEvent(new CommentCreatedEvent(
        saved.getId(), postId, request.parentId(), loginUserId));

    // 방금 만든 댓글은 반응이 없으므로 빈 맵.
    return CommentResponse.from(saved, Map.of());
  }

  // 1단계 깊이 불변식과 삭제/소속 검증을 한곳에 모은다. 순서: 소속 → 삭제 → 깊이.
  private void validateReplyTarget(Comment parent, Long postId) {
    if (!parent.getPost().getId().equals(postId)) {
      throw new BusinessException(ErrorCode.COMMENT_POST_MISMATCH);
    }
    if (parent.isDeleted()) {
      throw new BusinessException(ErrorCode.CANNOT_REPLY_TO_DELETED);
    }
    // 대댓글에 다시 답글을 다는 것을 금지 → 트리 깊이를 1단계로 강제
    if (parent.isReply()) {
      throw new BusinessException(ErrorCode.CANNOT_REPLY_TO_REPLY);
    }
  }

  // 단계 13: viewerId 추가(반응의 myReaction 매핑용, 비로그인 null).
  // 페이지의 모든 comment id(최상위 + 대댓글)를 모아 반응을 한 번에 집계(N+1 회피)한 뒤 응답에 주입한다.
  @Transactional(readOnly = true)
  public Page<CommentResponse> getComments(Long postId, Long viewerId, Pageable pageable) {
    if (!postRepository.existsById(postId)) {
      throw new NotFoundException(ErrorCode.POST_NOT_FOUND);
    }
    Page<Comment> page = commentRepository.findByPostIdAndParentIsNull(postId, pageable);

    List<Long> commentIds = new ArrayList<>();
    for (Comment root : page.getContent()) {
      commentIds.add(root.getId());
      // children은 @BatchSize로 IN 한 번에 로딩된다(트랜잭션 내라 안전).
      root.getChildren().forEach(reply -> commentIds.add(reply.getId()));
    }
    Map<Long, CommentReactionSummary> reactions =
        reactionService.getCommentReactions(commentIds, viewerId);

    return page.map(comment -> CommentResponse.from(comment, reactions));
  }

  // 인가(작성자만)는 컨트롤러의 @PreAuthorize(@commentSecurity)가 담당한다. 여기선 로직만.
  @Transactional
  public CommentResponse update(Long id, Long viewerId, CommentUpdateRequest request) {
    Comment comment = findComment(id);
    if (comment.isDeleted()) {
      throw new BusinessException(ErrorCode.CANNOT_EDIT_DELETED);
    }
    comment.update(request.content());
    // 단계 13: 수정은 반응을 바꾸지 않지만, 응답 스키마가 반응 필드를 노출하므로
    // 실제 카운트/내 반응을 조회해 채운다(0으로 덮어써 UI가 깜빡이는 것 방지).
    var reactions = reactionService.getCommentReactions(List.of(comment.getId()), viewerId);
    return CommentResponse.from(comment, reactions);
  }

  // 대댓글이 달린 원댓글을 물리 삭제하면 트리가 끊기므로 soft delete로 표시만 한다(내용은 응답에서 마스킹).
  @Transactional
  public void delete(Long id) {
    Comment comment = findComment(id);
    comment.softDelete();
  }

  private Comment findComment(Long id) {
    return commentRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.COMMENT_NOT_FOUND));
  }
}

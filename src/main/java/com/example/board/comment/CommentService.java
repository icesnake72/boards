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
import com.example.board.user.User;
import com.example.board.user.UserRepository;
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

    return CommentResponse.from(saved);
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

  @Transactional(readOnly = true)
  public Page<CommentResponse> getComments(Long postId, Pageable pageable) {
    if (!postRepository.existsById(postId)) {
      throw new NotFoundException(ErrorCode.POST_NOT_FOUND);
    }
    return commentRepository.findByPostIdAndParentIsNull(postId, pageable)
        .map(CommentResponse::from);
  }

  // 인가(작성자만)는 컨트롤러의 @PreAuthorize(@commentSecurity)가 담당한다. 여기선 로직만.
  @Transactional
  public CommentResponse update(Long id, CommentUpdateRequest request) {
    Comment comment = findComment(id);
    if (comment.isDeleted()) {
      throw new BusinessException(ErrorCode.CANNOT_EDIT_DELETED);
    }
    comment.update(request.content());
    return CommentResponse.from(comment);
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

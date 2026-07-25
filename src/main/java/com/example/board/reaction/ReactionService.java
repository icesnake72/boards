package com.example.board.reaction;

import com.example.board.comment.Comment;
import com.example.board.comment.CommentRepository;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.post.Post;
import com.example.board.post.PostRepository;
import com.example.board.reaction.dto.ReactionResponse;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 단계 13: 게시글/댓글 반응(유튜브식 토글). 정책: 상호배타(LIKE/DISLIKE 하나) + 같은 반응 재요청은 취소.
// 자기 글/댓글에 자기 반응도 허용한다(조회수와 달리 제외하지 않음).
@Service
@RequiredArgsConstructor
public class ReactionService {

  private final PostRepository postRepository;
  private final CommentRepository commentRepository;
  private final UserRepository userRepository;
  private final PostReactionRepository postReactionRepository;
  private final CommentReactionRepository commentReactionRepository;

  // 게시글 반응 토글. 없으면 생성, 같은 type이면 삭제(취소), 다른 type이면 전환.
  // JPQL 카운트 조회 직전 Hibernate가 pending 변경을 auto-flush 하므로 방금의 생성/삭제/전환이 카운트에 반영된다.
  @Transactional
  public ReactionResponse react(Long postId, Long userId, ReactionType type) {
    if (!postRepository.existsById(postId)) {
      throw new NotFoundException(ErrorCode.POST_NOT_FOUND);
    }
    postReactionRepository.findByPostIdAndUserId(postId, userId).ifPresentOrElse(
        existing -> {
          if (existing.getType() == type) {
            postReactionRepository.delete(existing);
          } else {
            existing.changeType(type);
          }
        },
        () -> {
          Post post = postRepository.getReferenceById(postId);
          User user = userRepository.getReferenceById(userId);
          postReactionRepository.save(new PostReaction(post, user, type));
        });
    return buildPostReactionResponse(postId, userId);
  }

  // 댓글 반응 토글 — 게시글과 동일 로직(대상만 Comment).
  @Transactional
  public ReactionResponse reactToComment(Long commentId, Long userId, ReactionType type) {
    if (!commentRepository.existsById(commentId)) {
      throw new NotFoundException(ErrorCode.COMMENT_NOT_FOUND);
    }
    commentReactionRepository.findByCommentIdAndUserId(commentId, userId).ifPresentOrElse(
        existing -> {
          if (existing.getType() == type) {
            commentReactionRepository.delete(existing);
          } else {
            existing.changeType(type);
          }
        },
        () -> {
          Comment comment = commentRepository.getReferenceById(commentId);
          User user = userRepository.getReferenceById(userId);
          commentReactionRepository.save(new CommentReaction(comment, user, type));
        });
    return buildCommentReactionResponse(commentId, userId);
  }

  // 게시글 단건 반응 요약(PostService.getPost가 호출). viewerId null이면 myReaction=null.
  @Transactional(readOnly = true)
  public PostReactionSummary getPostReaction(Long postId, Long viewerId) {
    long likeCount = postReactionRepository.countByPostIdAndType(postId, ReactionType.LIKE);
    long dislikeCount = postReactionRepository.countByPostIdAndType(postId, ReactionType.DISLIKE);
    ReactionType myReaction = viewerId == null ? null
        : postReactionRepository.findByPostIdAndUserId(postId, viewerId)
            .map(PostReaction::getType)
            .orElse(null);
    return new PostReactionSummary(likeCount, dislikeCount, myReaction);
  }

  // 댓글 목록 반응 일괄 조회(N+1 회피 핵심). 집계 쿼리 1번 + viewer 반응 in 쿼리 1번으로 조립한다.
  // CommentService가 페이지의 모든 comment id(최상위 + 대댓글)를 수집해 한 번만 호출한다.
  @Transactional(readOnly = true)
  public Map<Long, CommentReactionSummary> getCommentReactions(
      Collection<Long> commentIds, Long viewerId) {
    if (commentIds.isEmpty()) {
      return Map.of();
    }

    Map<Long, long[]> counts = new HashMap<>();
    for (CommentReactionCount row : commentReactionRepository.countByCommentIdIn(commentIds)) {
      long[] pair = counts.computeIfAbsent(row.getCommentId(), key -> new long[2]);
      if (row.getType() == ReactionType.LIKE) {
        pair[0] = row.getCnt();
      } else {
        pair[1] = row.getCnt();
      }
    }

    Map<Long, ReactionType> myReactions = new HashMap<>();
    if (viewerId != null) {
      List<CommentReaction> mine =
          commentReactionRepository.findByCommentIdInAndUserId(commentIds, viewerId);
      for (CommentReaction reaction : mine) {
        myReactions.put(reaction.getComment().getId(), reaction.getType());
      }
    }

    Map<Long, CommentReactionSummary> result = new HashMap<>();
    for (Long id : commentIds) {
      long[] pair = counts.getOrDefault(id, new long[2]);
      result.put(id, new CommentReactionSummary(pair[0], pair[1], myReactions.get(id)));
    }
    return result;
  }

  private ReactionResponse buildPostReactionResponse(Long postId, Long userId) {
    long likeCount = postReactionRepository.countByPostIdAndType(postId, ReactionType.LIKE);
    long dislikeCount = postReactionRepository.countByPostIdAndType(postId, ReactionType.DISLIKE);
    ReactionType myReaction = postReactionRepository.findByPostIdAndUserId(postId, userId)
        .map(PostReaction::getType)
        .orElse(null);
    return new ReactionResponse(likeCount, dislikeCount, myReaction);
  }

  private ReactionResponse buildCommentReactionResponse(Long commentId, Long userId) {
    long likeCount = commentReactionRepository.countByCommentIdAndType(commentId, ReactionType.LIKE);
    long dislikeCount =
        commentReactionRepository.countByCommentIdAndType(commentId, ReactionType.DISLIKE);
    ReactionType myReaction = commentReactionRepository.findByCommentIdAndUserId(commentId, userId)
        .map(CommentReaction::getType)
        .orElse(null);
    return new ReactionResponse(likeCount, dislikeCount, myReaction);
  }
}

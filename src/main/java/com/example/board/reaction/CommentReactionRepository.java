package com.example.board.reaction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentReactionRepository extends JpaRepository<CommentReaction, Long> {

  Optional<CommentReaction> findByCommentIdAndUserId(Long commentId, Long userId);

  long countByCommentIdAndType(Long commentId, ReactionType type);

  // 댓글 목록 N+1 회피용 집계 — commentId in (...) 한 번으로 (commentId, type)별 개수를 모은다.
  @Query("select r.comment.id as commentId, r.type as type, count(r) as cnt "
      + "from CommentReaction r where r.comment.id in :commentIds "
      + "group by r.comment.id, r.type")
  List<CommentReactionCount> countByCommentIdIn(@Param("commentIds") Collection<Long> commentIds);

  // viewer가 이 댓글들에 남긴 반응 일괄 조회(myReaction 매핑용) — in 쿼리 1번.
  List<CommentReaction> findByCommentIdInAndUserId(Collection<Long> commentIds, Long userId);
}

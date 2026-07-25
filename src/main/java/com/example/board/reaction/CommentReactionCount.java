package com.example.board.reaction;

// 댓글 목록 반응 집계 결과(interface projection). 댓글마다 쿼리를 날리는 N+1 대신,
// commentId in (...) 한 번으로 (commentId, type)별 개수를 받아 메모리에서 조립한다.
public interface CommentReactionCount {

  Long getCommentId();

  ReactionType getType();

  long getCnt();
}

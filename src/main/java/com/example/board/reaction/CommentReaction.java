package com.example.board.reaction;

import com.example.board.comment.Comment;
import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 단계 13: 댓글 반응. PostReaction과 동일 구조(대상만 Comment). (comment, user) 유니크.
@Entity
@Table(name = "comment_reactions", uniqueConstraints = @UniqueConstraint(
    name = "uk_comment_reactions_comment_user", columnNames = {"comment_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentReaction extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "comment_id", nullable = false)
  private Comment comment;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReactionType type;

  public CommentReaction(Comment comment, User user, ReactionType type) {
    this.comment = comment;
    this.user = user;
    this.type = type;
  }

  public void changeType(ReactionType type) {
    this.type = type;
  }
}

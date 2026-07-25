package com.example.board.reaction;

import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.post.Post;
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

// 단계 13: 게시글 반응. (post, user) 유니크로 한 사용자가 한 글에 반응 1건만 갖게 강제한다.
// 토글 취소는 행 삭제, 전환(LIKE↔DISLIKE)은 changeType로 처리한다.
@Entity
@Table(name = "post_reactions", uniqueConstraints = @UniqueConstraint(
    name = "uk_post_reactions_post_user", columnNames = {"post_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostReaction extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "post_id", nullable = false)
  private Post post;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReactionType type;

  public PostReaction(Post post, User user, ReactionType type) {
    this.post = post;
    this.user = user;
    this.type = type;
  }

  // LIKE↔DISLIKE 전환. dirty checking으로 커밋 시 UPDATE 된다.
  public void changeType(ReactionType type) {
    this.type = type;
  }
}

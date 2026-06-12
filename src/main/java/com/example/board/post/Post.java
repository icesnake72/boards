package com.example.board.post;

import com.example.board.board.Board;
import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // LAZY: 목록 조회 시 Post마다 Board/User를 즉시 로딩하면 N+1 발생.
  // 필요한 곳에서만 fetch join / @EntityGraph로 함께 가져온다.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "board_id", nullable = false)
  private Board board;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User author;

  @Column(nullable = false, length = 200)
  private String title;

  @Lob
  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(nullable = false)
  private int viewCount;

  public Post(Board board, User author, String title, String content) {
    this.board = board;
    this.author = author;
    this.title = title;
    this.content = content;
    this.viewCount = 0;
  }

  public void update(String title, String content) {
    this.title = title;
    this.content = content;
  }

  public void increaseViewCount() {
    this.viewCount++;
  }

  // FK(user_id)만 비교하므로 LAZY 프록시여도 author 추가 조회가 발생하지 않는다
  public boolean isAuthor(Long userId) {
    return author.getId().equals(userId);
  }
}

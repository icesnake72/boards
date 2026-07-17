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
import jakarta.persistence.CascadeType;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

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

  // 단계 10: 첨부 이미지(양방향). 목록 조회의 썸네일 때문에 이 컬렉션에 접근하지만,
  // 페이징 쿼리에 컬렉션을 fetch join 하면 MultipleBagFetchException/메모리 페이징 함정에 빠진다.
  // 그래서 목록은 @EntityGraph(board, author)만 join 하고, images는 @BatchSize로
  // 여러 Post의 것을 IN 쿼리 한 번에 로딩해 N+1을 완화한다(단건은 findDetailById에서 fetch join).
  @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sortOrder asc")
  @BatchSize(size = 100)
  private List<PostImage> images = new ArrayList<>();

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

  // 양방향 편의 메서드 — 컬렉션 추가와 연관관계 주인(PostImage.post) 세팅을 한 번에 처리
  public void addImage(PostImage image) {
    images.add(image);
    image.assignPost(this);
  }

  public void increaseViewCount() {
    this.viewCount++;
  }

  // FK(user_id)만 비교하므로 LAZY 프록시여도 author 추가 조회가 발생하지 않는다
  public boolean isAuthor(Long userId) {
    return author.getId().equals(userId);
  }
}

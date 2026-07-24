package com.example.board.comment;

import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.post.Post;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

// 단계 11: 댓글 + 대댓글(1단계). parent 자기참조로 계층을 표현하되,
// "대댓글의 대댓글"은 없다는 불변식은 서비스(CommentService.create)가 강제한다(엔티티는 구조만 제공).
@Entity
@Table(name = "comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // LAZY: 목록/상세에서 필요한 연관만 fetch join/EntityGraph로 가져온다(N+1 회피).
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "post_id", nullable = false)
  private Post post;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User author;

  // 자기참조. 최상위 댓글이면 null, 대댓글이면 부모 댓글을 가리킨다.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id")
  private Comment parent;

  // 대댓글 컬렉션. 최상위 댓글만 페이징 조회하고(findByPostIdAndParentIsNull),
  // 각 원댓글의 children은 @BatchSize로 여러 부모의 것을 IN 쿼리 한 번에 로딩해 N+1을 완화한다.
  @OneToMany(mappedBy = "parent")
  @OrderBy("createdAt asc")
  @BatchSize(size = 100)
  private List<Comment> children = new ArrayList<>();

  @Lob
  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  // soft delete: 대댓글이 달린 원댓글을 물리 삭제하면 트리가 끊기므로, 삭제 표시만 하고 내용을 마스킹한다.
  @Column(nullable = false)
  private boolean deleted;

  // 최상위 댓글 생성자(parent = null). 대댓글은 assignParent로 부모를 세팅한다.
  public Comment(Post post, User author, String content) {
    this.post = post;
    this.author = author;
    this.content = content;
    this.deleted = false;
  }

  // 양방향 편의 메서드 — 부모의 children 컬렉션 추가와 자식의 parent 세팅을 한 번에 처리한다.
  // 깊이 제한/삭제/소속 검증은 서비스가 수행한 뒤 호출한다.
  // 같은 트랜잭션 안에서 원댓글을 다시 조회해 children을 읽어도 방금 단 대댓글이 보이도록 메모리 상태를 맞춘다.
  public void addReply(Comment reply) {
    children.add(reply);
    reply.parent = this;
  }

  public void update(String content) {
    this.content = content;
  }

  public void softDelete() {
    this.deleted = true;
  }

  public boolean isRoot() {
    return parent == null;
  }

  public boolean isReply() {
    return parent != null;
  }

  // FK(user_id)만 비교하므로 LAZY 프록시여도 author 추가 조회가 발생하지 않는다.
  // userId가 null(비로그인)이면 false.
  public boolean isAuthor(Long userId) {
    return author.getId().equals(userId);
  }
}

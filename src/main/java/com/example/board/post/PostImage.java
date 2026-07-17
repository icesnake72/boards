package com.example.board.post;

import com.example.board.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 단계 10: 게시글 첨부 이미지. 실제 파일은 로컬 디스크(app.upload.dir)에 저장하고
// DB에는 메타데이터(저장명/원본명/타입/크기/정렬순서)만 보관한다.
@Entity
@Table(name = "post_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostImage extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "post_id", nullable = false)
  private Post post;

  // 디스크에 실제 저장된 파일명(UUID 기반). 정적 서빙 URL(/images/{storedName})의 키가 된다.
  @Column(nullable = false)
  private String storedName;

  private String originalName;

  private String contentType;

  private long size;

  @Column(nullable = false)
  private int sortOrder;

  public PostImage(
      String storedName, String originalName, String contentType, long size, int sortOrder) {
    this.storedName = storedName;
    this.originalName = originalName;
    this.contentType = contentType;
    this.size = size;
    this.sortOrder = sortOrder;
  }

  // 양방향 연관관계의 주인 세팅은 Post.addImage에서 이 메서드로 수행한다(같은 패키지 전용).
  void assignPost(Post post) {
    this.post = post;
  }
}

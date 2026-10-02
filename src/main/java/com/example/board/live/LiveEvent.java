package com.example.board.live;

import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 단계 18: 진행자(host)가 여는 라이브 세션. 참여자는 code로 찾아 들어온다.
@Entity
@Table(name = "live_events", indexes = @Index(name = "idx_live_events_host", columnList = "host_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveEvent extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "host_id", nullable = false)
  private User host;

  @Column(nullable = false, length = 100)
  private String title;

  @Column(nullable = false, unique = true, length = 6)
  private String code;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 10)
  private LiveStatus status;

  public LiveEvent(User host, String title, String code) {
    this.host = host;
    this.title = title;
    this.code = code;
    this.status = LiveStatus.OPEN;
  }

  public boolean isOpen() {
    return status == LiveStatus.OPEN;
  }

  // LAZY 프록시의 getId()는 초기화 없이 FK 값만 돌려준다 — 추가 SELECT 없음.
  public boolean isHostedBy(Long userId) {
    return Objects.equals(host.getId(), userId);
  }

  public void close() {
    this.status = LiveStatus.CLOSED;
  }

  public void assertOpen() {
    if (!isOpen()) {
      throw new BusinessException(ErrorCode.LIVE_EVENT_CLOSED);
    }
  }
}

package com.example.board.board;
// ↑ 규칙 1: 테스트는 src/test/java 아래, "테스트 대상과 같은 패키지"에 둔다.
//   같은 패키지면 package-private 멤버에도 접근할 수 있고, 어떤 클래스의 테스트인지 바로 보인다.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.board.dto.BoardCreateRequest;
import com.example.board.board.dto.BoardResponse;
import com.example.board.board.dto.BoardUpdateRequest;
import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// ── 학생용 최소 테스트 예제 ──────────────────────────────────────────────────
// 규칙 2: 클래스 이름은 "대상클래스 + Test" (BoardService → BoardServiceTest).
//   ./gradlew test 가 이 네이밍의 클래스를 자동으로 찾아 실행한다.
//
// @SpringBootTest   : 실제 스프링 컨텍스트를 띄운다 → @Autowired로 진짜 빈을 받아 테스트.
//                     DB는 src/test/resources/application.yaml 이 H2(인메모리)로 바꿔주므로
//                     MySQL 없이도 어디서든(로컬·CI) 돈다.
// @Transactional    : 각 테스트를 트랜잭션으로 감싸고 끝나면 롤백 → 테스트끼리 데이터가
//                     섞이지 않는다(격리). "저장했는데 다음 테스트에 남아있으면?"을 걱정할 필요 없음.
@SpringBootTest
@Transactional
class BoardServiceTest {

  @Autowired BoardService boardService;   // 테스트 대상(실제 빈)

  // 규칙 3: 메서드 이름만 읽어도 시나리오가 보이게 —
  //   should_기대결과_when상황() 패턴. @DisplayName으로 한글 설명을 붙여도 좋다.
  // 규칙 4: 본문은 given(준비) → when(실행) → then(검증) 세 단락으로 나눈다.
  @Test
  @DisplayName("게시판을 생성하면 이름·설명이 저장되고 id가 발급된다")
  void should_createBoard_whenValidRequest() {
    // given — 테스트에 필요한 입력을 준비한다
    BoardCreateRequest request = new BoardCreateRequest("공부방", "스터디 모집");

    // when — 딱 하나의 행동(테스트 대상 메서드)을 실행한다
    BoardResponse response = boardService.create(request);

    // then — 결과를 검증한다. assertThat(실제값).isEqualTo(기대값) 가 기본형.
    assertThat(response.id()).isNotNull();            // DB가 id를 발급했는가
    assertThat(response.name()).isEqualTo("공부방");
    assertThat(response.description()).isEqualTo("스터디 모집");
  }

  // 실패 경로도 반드시 테스트한다 — "예외가 나는 것"이 정상 동작인 시나리오.
  // assertThatThrownBy(실행).isInstanceOf(기대예외) 가 예외 검증의 기본형.
  @Test
  @DisplayName("같은 이름의 게시판을 또 만들면 DuplicateException")
  void should_throwDuplicate_whenNameAlreadyExists() {
    // given — 먼저 하나 만들어 두고
    boardService.create(new BoardCreateRequest("공부방", null));

    // when + then — 같은 이름으로 또 만들면 예외
    assertThatThrownBy(() -> boardService.create(new BoardCreateRequest("공부방", "다른 설명")))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  @DisplayName("없는 id를 조회하면 NotFoundException")
  void should_throwNotFound_whenBoardDoesNotExist() {
    assertThatThrownBy(() -> boardService.getBoard(999_999L))
        .isInstanceOf(NotFoundException.class);
  }

  // 수정은 "바꾼 뒤 다시 조회해서" 반영을 확인한다 — JPA dirty checking(save 호출 없이
  // 트랜잭션 커밋 시 UPDATE)이 실제로 동작하는지까지 함께 검증되는 셈.
  @Test
  @DisplayName("게시판을 수정하면 조회 결과에 반영된다")
  void should_updateBoard_whenValidRequest() {
    // given
    Long id = boardService.create(new BoardCreateRequest("공부방", "스터디 모집")).id();

    // when
    boardService.update(id, new BoardUpdateRequest("자유방", "잡담 환영"));

    // then
    BoardResponse found = boardService.getBoard(id);
    assertThat(found.name()).isEqualTo("자유방");
    assertThat(found.description()).isEqualTo("잡담 환영");
  }
}

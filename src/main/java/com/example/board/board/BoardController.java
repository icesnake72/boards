package com.example.board.board;

import com.example.board.auth.SessionConst;
import com.example.board.board.dto.BoardCreateRequest;
import com.example.board.board.dto.BoardResponse;
import com.example.board.board.dto.BoardUpdateRequest;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@RequestMapping("/api/v1/boards")
@RequiredArgsConstructor
public class BoardController {

  private final BoardService boardService;

  @GetMapping
  public List<BoardResponse> getBoards() {
    return boardService.getBoards();
  }

  @GetMapping("/{id}")
  public BoardResponse getBoard(@PathVariable Long id) {
    return boardService.getBoard(id);
  }

  // 게시판 생성/수정/삭제는 ADMIN 전용 — Role 검증은 서비스에서 수행
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BoardResponse create(
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody BoardCreateRequest request) {
    return boardService.create(requireLogin(loginUserId), request);
  }

  @PutMapping("/{id}")
  public BoardResponse update(
      @PathVariable Long id,
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody BoardUpdateRequest request) {
    return boardService.update(id, requireLogin(loginUserId), request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable Long id,
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId) {
    boardService.delete(id, requireLogin(loginUserId));
  }

  private Long requireLogin(Long loginUserId) {
    if (loginUserId == null) {
      throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    }
    return loginUserId;
  }
}

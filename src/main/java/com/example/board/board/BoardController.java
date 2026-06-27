package com.example.board.board;

import com.example.board.board.dto.BoardCreateRequest;
import com.example.board.board.dto.BoardResponse;
import com.example.board.board.dto.BoardUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

  // 게시판 생성/수정/삭제는 ADMIN 전용 — 단계 6: 역할 기반 인가를 URL 규칙에서 메서드 보안으로 이동.
  @PreAuthorize("hasRole('ADMIN')")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BoardResponse create(@Valid @RequestBody BoardCreateRequest request) {
    return boardService.create(request);
  }

  @PreAuthorize("hasRole('ADMIN')")
  @PutMapping("/{id}")
  public BoardResponse update(
      @PathVariable Long id,
      @Valid @RequestBody BoardUpdateRequest request) {
    return boardService.update(id, request);
  }

  @PreAuthorize("hasRole('ADMIN')")
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    boardService.delete(id);
  }
}

package com.example.board.board;

import com.example.board.board.dto.BoardCreateRequest;
import com.example.board.board.dto.BoardResponse;
import com.example.board.board.dto.BoardUpdateRequest;
import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 강의 포인트: ADMIN 인가는 SecurityConfig가 선언적으로 담당하므로 서비스는 비즈니스 규칙(중복 이름)만 검증한다.
@Service
@RequiredArgsConstructor
public class BoardService {

  private final BoardRepository boardRepository;

  @Transactional(readOnly = true)
  public List<BoardResponse> getBoards() {
    return boardRepository.findAll().stream().map(BoardResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public BoardResponse getBoard(Long id) {
    return BoardResponse.from(findBoard(id));
  }

  @Transactional
  public BoardResponse create(BoardCreateRequest request) {
    if (boardRepository.existsByName(request.name())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_BOARD_NAME);
    }
    Board board = boardRepository.save(new Board(request.name(), request.description()));
    return BoardResponse.from(board);
  }

  @Transactional
  public BoardResponse update(Long id, BoardUpdateRequest request) {
    Board board = findBoard(id);
    if (!board.getName().equals(request.name()) && boardRepository.existsByName(request.name())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_BOARD_NAME);
    }
    board.update(request.name(), request.description());
    return BoardResponse.from(board);
  }

  @Transactional
  public void delete(Long id) {
    boardRepository.delete(findBoard(id));
  }

  private Board findBoard(Long id) {
    return boardRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.BOARD_NOT_FOUND));
  }
}

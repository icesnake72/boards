package com.example.board.board;

import com.example.board.board.dto.BoardCreateRequest;
import com.example.board.board.dto.BoardResponse;
import com.example.board.board.dto.BoardUpdateRequest;
import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.ForbiddenException;
import com.example.board.global.exception.NotFoundException;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BoardService {

  private final BoardRepository boardRepository;
  private final UserRepository userRepository;

  @Transactional(readOnly = true)
  public List<BoardResponse> getBoards() {
    return boardRepository.findAll().stream().map(BoardResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public BoardResponse getBoard(Long id) {
    return BoardResponse.from(findBoard(id));
  }

  @Transactional
  public BoardResponse create(Long loginUserId, BoardCreateRequest request) {
    validateAdmin(loginUserId);
    if (boardRepository.existsByName(request.name())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_BOARD_NAME);
    }
    Board board = boardRepository.save(new Board(request.name(), request.description()));
    return BoardResponse.from(board);
  }

  @Transactional
  public BoardResponse update(Long id, Long loginUserId, BoardUpdateRequest request) {
    validateAdmin(loginUserId);
    Board board = findBoard(id);
    if (!board.getName().equals(request.name()) && boardRepository.existsByName(request.name())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_BOARD_NAME);
    }
    board.update(request.name(), request.description());
    return BoardResponse.from(board);
  }

  @Transactional
  public void delete(Long id, Long loginUserId) {
    validateAdmin(loginUserId);
    boardRepository.delete(findBoard(id));
  }

  private void validateAdmin(Long userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    if (user.getRole() != Role.ADMIN) {
      throw new ForbiddenException(ErrorCode.ADMIN_ONLY);
    }
  }

  private Board findBoard(Long id) {
    return boardRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.BOARD_NOT_FOUND));
  }
}

package com.sbs.board.board;

import com.sbs.board.auth.UserRepository;
import com.sbs.board.board.dto.BoardDTO;
import com.sbs.board.board.dto.BoardRequest;
import com.sbs.board.board.dto.BoardResponse;
import com.sbs.board.global.entity.Board;
import com.sbs.board.global.entity.User;
import com.sbs.board.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BoardService {
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;

    public void validateUser(Long loginUserId) {
        User user = userRepository.findById(loginUserId)
                .orElseThrow(()->new NotFoundException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() != User.Role.ADMIN) {
            throw new ForbiddenException(ErrorCode.BOARD_ACCESS_DENIED);
        }
    }


    // Create
    @Transactional
    public BoardResponse create(Long loginUserId, BoardRequest request) {
        validateUser(loginUserId);

        if (boardRepository.existsByName(request.getName())) {
            // return conflict error
            throw new DuplicateException(ErrorCode.DUPLICATE_BOARD_NAME);
        }

        Board board = new Board();
        board.setName(request.getName());
        board.setDescription(request.getDescription());

        Board savedBoard = boardRepository.save( board );

        BoardResponse response = new BoardResponse();
        response.setId(savedBoard.getId());
        response.setName(savedBoard.getName());
        response.setDescription(savedBoard.getDescription());
        response.setCreatedAt(savedBoard.getCreatedAt().toString());

        //
        return response;
    }

    // Read
    @Transactional(readOnly = true)
    public List<BoardResponse> list() {
        return boardRepository.findAll().stream().map(Board::toDTO).toList();
    }

    // Update
    @Transactional
    public BoardResponse update(Long loginUserId, Long boardId, BoardRequest request) {
        validateUser(loginUserId);

        Board board = boardRepository.findById(boardId)
                .orElseThrow(()->new NotFoundException(ErrorCode.BOARD_NOT_FOUND));

        board.setName(request.getName());
        board.setDescription(request.getDescription());
        Board savedBoard = boardRepository.save( board );

        return Board.toDTO(savedBoard);
    }

    @Transactional
    public String delete(Long loginUserId, Long boardId) {
        validateUser(loginUserId);

        Board board = boardRepository.findById(boardId)
                .orElseThrow(()->new NotFoundException(ErrorCode.BOARD_NOT_FOUND));

        boardRepository.delete(board);

        return "ok";
    }

    // Delete
}

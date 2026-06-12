package com.example.board.board.dto;

import com.example.board.board.Board;
import java.time.LocalDateTime;

public record BoardResponse(
    Long id,
    String name,
    String description,
    LocalDateTime createdAt
) {

  public static BoardResponse from(Board board) {
    return new BoardResponse(board.getId(), board.getName(), board.getDescription(), board.getCreatedAt());
  }
}

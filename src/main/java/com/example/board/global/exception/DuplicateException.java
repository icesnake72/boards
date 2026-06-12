package com.example.board.global.exception;

public class DuplicateException extends BusinessException {

  public DuplicateException(ErrorCode errorCode) {
    super(errorCode);
  }
}

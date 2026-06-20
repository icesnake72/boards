package com.sbs.board.global.exception;

public class ForbiddenException extends BusinessException {
    public ForbiddenException(ErrorCode errorCode) {
        super(errorCode);
    }
}

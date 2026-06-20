package com.sbs.board.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

import static com.sbs.board.global.exception.ErrorCode.*;


@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ErrorResponse> handleNullPointerError(NullPointerException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(INTERNAL_SERVER_ERROR));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(INVALID_INPUT));
    }


    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessError(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(SQLIntegrityConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleSqlIntegrityError(SQLIntegrityConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(SQL_INTEGRITY_ERROR));
    }

//    @ExceptionHandler(NotFoundUserException.class)
//    public ResponseEntity<ErrorResponse> handleNotFoundUserError(NotFoundUserException ex) {
//        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(USER_NOT_FOUND));
//    }
//
//    @ExceptionHandler(UnauthorizedException.class)
//    public ResponseEntity<ErrorResponse> handleUnauthorizedError(UnauthorizedException ex) {
//        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(LOGIN_FAILED));
//    }
}

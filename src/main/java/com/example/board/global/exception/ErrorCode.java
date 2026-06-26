package com.example.board.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "프로필을 찾을 수 없습니다."),
  BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "게시판을 찾을 수 없습니다."),
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),

  DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 username입니다."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 email입니다."),
  DUPLICATE_BOARD_NAME(HttpStatus.CONFLICT, "이미 존재하는 게시판 이름입니다."),
  NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 nickname입니다."),

  POST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "게시글에 대한 권한이 없습니다."),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "username 또는 password가 올바르지 않습니다."),
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh token입니다."),
  EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 refresh token입니다. 다시 로그인하세요."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽을 수 없습니다."),
  TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "요청 값의 타입이 올바르지 않습니다."),
  MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 미디어 타입입니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String message;
}

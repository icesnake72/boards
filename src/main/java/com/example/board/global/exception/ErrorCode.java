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
  COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
  // 단계 12: 알림 — 남의 알림 존재 유출을 막기 위해 소유 검증 실패도 이 코드(404)로 통일한다(열거 방어).
  NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
  // 단계 7에서 발견: 매핑 없는 URL이 500으로 새던 것을 404로 교정 (NoResourceFoundException 핸들러)
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

  DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 username입니다."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 email입니다."),
  DUPLICATE_BOARD_NAME(HttpStatus.CONFLICT, "이미 존재하는 게시판 이름입니다."),
  NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 nickname입니다."),

  // 단계 6에서 작성자 거부가 메서드 보안(@PreAuthorize)로 이동하며 ACCESS_DENIED로 통합됨. 미사용이나 보존.
  POST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "게시글에 대한 권한이 없습니다."),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

  // 단계 7: 카카오 OAuth2 — 토큰 교환/사용자 정보 조회 실패, 동의 거부 등을 한 코드로 묶는다(내부 사유는 서버 로그로)
  OAUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다."),
  INVALID_OAUTH_STATE(HttpStatus.UNAUTHORIZED, "OAuth state 검증에 실패했습니다. 처음부터 다시 시도하세요."),

  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "username 또는 password가 올바르지 않습니다."),
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 refresh token입니다."),
  // 단계 15 처리에 의해 미사용 — TTL 만료 시 키가 사라져 "없음=무효(INVALID)"로 단일화됨. 교육용으로 상수는 보존.
  EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 refresh token입니다. 다시 로그인하세요."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  // 단계 11: 댓글/대댓글 — 1단계 깊이 불변식과 삭제된 댓글에 대한 제약을 별도 코드로 구분한다.
  CANNOT_REPLY_TO_REPLY(HttpStatus.BAD_REQUEST, "대댓글에는 답글을 달 수 없습니다."),
  CANNOT_REPLY_TO_DELETED(HttpStatus.BAD_REQUEST, "삭제된 댓글에는 답글을 달 수 없습니다."),
  CANNOT_EDIT_DELETED(HttpStatus.BAD_REQUEST, "삭제된 댓글은 수정할 수 없습니다."),
  COMMENT_POST_MISMATCH(HttpStatus.BAD_REQUEST, "부모 댓글이 해당 게시글의 댓글이 아닙니다."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽을 수 없습니다."),
  TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "요청 값의 타입이 올바르지 않습니다."),
  MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 미디어 타입입니다."),

  // 단계 10: 파일 업로드
  INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "허용되지 않는 파일 형식입니다."),
  FILE_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "첨부 가능한 이미지 개수를 초과했습니다."),
  FILE_SIZE_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 가능한 파일 크기를 초과했습니다."),
  FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다."),

  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String message;
}

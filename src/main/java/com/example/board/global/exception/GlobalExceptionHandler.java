package com.example.board.global.exception;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  // UnauthorizedException(401), ForbiddenException(403) 등 모든 도메인 예외를
  // ErrorCode에 정의된 HTTP 상태로 변환한다
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("BusinessException: code={}, message={}", errorCode.name(), e.getMessage());
    return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
        .map(error -> new ErrorResponse.FieldErrorDetail(error.getField(), error.getDefaultMessage()))
        .toList();
    log.warn("Validation failed: {}", errors);
    return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
        .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errors));
  }

  // 깨지거나 비어 있는 JSON 본문 등으로 요청 본문을 역직렬화할 수 없을 때 발생
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e) {
    log.warn("Malformed request body: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.getStatus())
        .body(ErrorResponse.of(ErrorCode.MALFORMED_REQUEST));
  }

  // 경로 변수/요청 파라미터의 타입이 맞지 않을 때 발생 (예: Long 자리에 "abc")
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    log.warn("Type mismatch: name={}, value={}", e.getName(), e.getValue());
    return ResponseEntity.status(ErrorCode.TYPE_MISMATCH.getStatus())
        .body(ErrorResponse.of(ErrorCode.TYPE_MISMATCH));
  }

  // 필수 쿼리 파라미터가 누락되었을 때 발생
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException e) {
    log.warn("Missing request parameter: {}", e.getParameterName());
    return ResponseEntity.status(ErrorCode.MISSING_PARAMETER.getStatus())
        .body(ErrorResponse.of(ErrorCode.MISSING_PARAMETER));
  }

  // 해당 경로가 지원하지 않는 HTTP 메서드로 호출되었을 때 발생 (예: POST 전용에 DELETE)
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException e) {
    log.warn("Method not supported: {}", e.getMethod());
    return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
        .body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
  }

  // 지원하지 않는 Content-Type으로 요청 본문이 전송되었을 때 발생
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException e) {
    log.warn("Unsupported media type: {}", e.getContentType());
    return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getStatus())
        .body(ErrorResponse.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
  }

  // 단계 6: @PreAuthorize 거부는 컨트롤러 진입 시점에 AuthorizationDeniedException(AccessDeniedException의 하위)을
  // 던지므로 RestAccessDeniedHandler(필터 단계)가 아닌 여기서 잡힌다. 403 ACCESS_DENIED로 일원화한다.
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
    log.warn("Access denied: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
        .body(ErrorResponse.of(ErrorCode.ACCESS_DENIED));
  }

  // 매핑된 핸들러가 없는 경로는 정적 리소스 탐색까지 실패한 뒤 이 예외로 온다 (Spring 6.1+).
  // 이 핸들러가 없으면 최후 방어선(Exception → 500)에 걸려 단순 오타 URL이 500 + ERROR 스택트레이스가 된다.
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
    log.warn("No handler for path: {}", e.getResourcePath());
    return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
        .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
  }

  // 예상하지 못한 예외는 상세를 숨기고 로그만 남긴다 (보안상 내부 정보 노출 금지)
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    log.error("Unexpected exception", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
  }
}

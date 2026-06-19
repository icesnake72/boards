package com.example.board.auth;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

// @LoginUserId Long 파라미터에 로그인 사용자 id를 주입하는 책임을 한 곳에 모은다.
@Component
public class LoginUserIdArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(LoginUserId.class)
        && parameter.getParameterType().equals(Long.class);
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    // getSession(false): 세션이 없으면 새로 만들지 않는다.
    HttpSession session = request != null ? request.getSession(false) : null;
    Long loginUserId =
        session != null ? (Long) session.getAttribute(SessionConst.LOGIN_USER_ID) : null;
    if (loginUserId == null) {
      throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    }
    // 강의 포인트: 지금은 세션에서 꺼내지만, JWT 단계에서는 이 메서드 내부만 토큰 파싱으로 바꾸면 된다 — 컨트롤러는 그대로.
    return loginUserId;
  }
}

package com.example.board.auth;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
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
    // 강의 포인트: 단계 1은 세션에서 꺼냈지만, 단계 2는 JwtAuthenticationFilter가 토큰에서 꺼내
    // request attribute에 심어둔 userId를 읽는다. 인증 방식이 바뀌어도 컨트롤러는 그대로다.
    Long loginUserId =
        request != null ? (Long) request.getAttribute(AuthConst.LOGIN_USER_ID) : null;
    if (loginUserId == null) {
      throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    }
    return loginUserId;
  }
}

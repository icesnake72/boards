package com.example.board.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 로그인한 사용자의 id를 주입. 단계 2(JWT)에서는 필터가 토큰에서 꺼내 심은 LOGIN_USER_ID를 읽는다. 비로그인 시 401.
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUserId {
}

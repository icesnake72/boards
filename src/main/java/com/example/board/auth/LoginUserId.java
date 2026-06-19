package com.example.board.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 로그인한 사용자의 id를 주입. 세션(단계 1)에 저장된 LOGIN_USER_ID를 꺼낸다. 비로그인 시 401.
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginUserId {
}

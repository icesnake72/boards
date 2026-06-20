package com.example.board.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

// Spring MVC 설정 클래스.
// 단계 3에서는 로그인 사용자 주입을 Spring Security 표준(@AuthenticationPrincipal)이 담당하므로
// 커스텀 ArgumentResolver 등록은 사라지고, Page를 안정적인 JSON 구조(PagedModel)로 직렬화하는 설정만 남는다.
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig {
}

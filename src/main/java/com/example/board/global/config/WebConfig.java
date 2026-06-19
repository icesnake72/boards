package com.example.board.global.config;

import com.example.board.auth.LoginUserIdArgumentResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Spring MVC 설정 클래스. WebMvcConfigurer를 구현하면 MVC의 동작을 커스터마이즈할 수 있다.
// 실행 시점: 앱 기동 시 Spring이 이 설정을 읽어 MVC를 구성한다 (요청 처리 전, 단 한 번).
// 역할 (1) Page를 안정적인 JSON 구조(PagedModel)로 직렬화 (2) @LoginUserId Resolver 등록
@Configuration
@RequiredArgsConstructor
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

  // 생성자 주입: @LoginUserId 파라미터를 해석하는 Resolver 빈 (앱 기동 시 1회 주입)
  private final LoginUserIdArgumentResolver loginUserIdArgumentResolver;

  // 기동 시 한 번 호출되어 우리 Resolver를 MVC에 등록한다.
  // 등록 후에는 매 요청마다(컨트롤러 직전) Spring이 @LoginUserId 파라미터를 만나면 이 Resolver를 사용한다.
  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(loginUserIdArgumentResolver);
  }
}

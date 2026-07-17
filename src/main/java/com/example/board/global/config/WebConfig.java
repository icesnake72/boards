package com.example.board.global.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Spring MVC 설정 클래스.
// 단계 3: Page를 안정적인 JSON 구조(PagedModel)로 직렬화(VIA_DTO).
// 단계 10: 업로드된 이미지를 /images/** 로 정적 서빙 — 물리 경로는 app.upload.dir.
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig implements WebMvcConfigurer {

  private final String uploadDir;

  public WebConfig(@Value("${app.upload.dir}") String uploadDir) {
    this.uploadDir = uploadDir;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
    // toUri()로 정규 file URI를 만든다 — Windows 경로(C:\...\역슬래시)도 안전하게 처리되고
    // 디렉터리에 대한 toUri()는 항상 슬래시로 끝난다(FileStorageService가 기동 시 디렉터리를 생성해 둠).
    String location = root.toUri().toString();
    registry.addResourceHandler("/images/**")
        .addResourceLocations(location);
  }
}

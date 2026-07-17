package com.example.board.global.storage;

import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

// 단계 10: 로컬 디스크 파일 저장소.
// - 이미지 MIME/확장자 화이트리스트만 허용
// - 저장 파일명은 UUID로 생성(원본명을 경로에 절대 사용하지 않음 → path traversal 방어)
// - 최종 경로가 업로드 루트 하위인지 normalize 후 재검증
@Slf4j
@Service
public class FileStorageService {

  private static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
  private static final Set<String> ALLOWED_EXTENSIONS =
      Set.of("png", "jpg", "jpeg", "gif", "webp");

  private final Path uploadRoot;

  public FileStorageService(@Value("${app.upload.dir}") String uploadDir) {
    this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
  }

  @PostConstruct
  public void init() {
    try {
      Files.createDirectories(uploadRoot);
      log.info("File upload directory ready: {}", uploadRoot);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
    }
  }

  public String store(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
    }
    String contentType = file.getContentType();
    if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
      throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
    }
    String extension = extractExtension(file.getOriginalFilename());
    if (!ALLOWED_EXTENSIONS.contains(extension)) {
      throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
    }

    String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
    Path target = uploadRoot.resolve(storedName).normalize();
    // UUID라 정상 경로는 항상 루트 하위지만, 방어적으로 재확인한다(경로 이탈 차단)
    if (!target.startsWith(uploadRoot)) {
      throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
    }

    try (InputStream in = file.getInputStream()) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      return storedName;
    } catch (IOException e) {
      log.error("Failed to store file: storedName={}", storedName, e);
      throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
    }
  }

  // best-effort 삭제 — 실패해도 예외를 전파하지 않고 로그만 남긴다(DB 롤백 보상용).
  public void delete(String storedName) {
    if (storedName == null || storedName.isBlank()) {
      return;
    }
    try {
      Path target = uploadRoot.resolve(storedName).normalize();
      if (!target.startsWith(uploadRoot)) {
        log.warn("Refused to delete file outside upload root: {}", storedName);
        return;
      }
      Files.deleteIfExists(target);
    } catch (IOException e) {
      log.warn("Failed to delete file: storedName={}", storedName, e);
    }
  }

  private String extractExtension(String originalName) {
    if (originalName == null) {
      return "";
    }
    int dot = originalName.lastIndexOf('.');
    if (dot < 0 || dot == originalName.length() - 1) {
      return "";
    }
    return originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }
}

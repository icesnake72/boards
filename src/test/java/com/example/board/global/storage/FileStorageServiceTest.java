package com.example.board.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class FileStorageServiceTest {

  @TempDir
  Path tempDir;

  FileStorageService fileStorageService;

  @BeforeEach
  void setUp() {
    fileStorageService = new FileStorageService(tempDir.toString());
    fileStorageService.init();
  }

  @Test
  void should_storeImage_andCreateFileUnderRoot() {
    MockMultipartFile image = new MockMultipartFile(
        "images", "photo.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1, 2, 3});

    String storedName = fileStorageService.store(image);

    assertThat(storedName).endsWith(".png");
    Path stored = tempDir.resolve(storedName);
    assertThat(Files.exists(stored)).isTrue();
  }

  @Test
  void should_reject_nonImageContentType() {
    MockMultipartFile textFile = new MockMultipartFile(
        "images", "note.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes());

    assertThatThrownBy(() -> fileStorageService.store(textFile))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void should_reject_emptyFile() {
    MockMultipartFile empty = new MockMultipartFile(
        "images", "photo.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);

    assertThatThrownBy(() -> fileStorageService.store(empty))
        .isInstanceOf(BusinessException.class);
  }

  // path traversal: 원본 파일명이 "../"를 포함해도 UUID로 저장되므로 파일이 루트를 벗어나지 않는다.
  @Test
  void should_notEscapeRoot_whenOriginalNameContainsTraversal() {
    MockMultipartFile malicious = new MockMultipartFile(
        "images", "../../evil.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1});

    String storedName = fileStorageService.store(malicious);

    assertThat(storedName).doesNotContain("..").doesNotContain("/");
    Path stored = tempDir.resolve(storedName).normalize();
    assertThat(stored.startsWith(tempDir)).isTrue();
    assertThat(Files.exists(stored)).isTrue();
  }

  @Test
  void should_deleteStoredFile() {
    MockMultipartFile image = new MockMultipartFile(
        "images", "photo.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1});
    String storedName = fileStorageService.store(image);

    fileStorageService.delete(storedName);

    assertThat(Files.exists(tempDir.resolve(storedName))).isFalse();
  }

  @Test
  void should_notThrow_whenDeletingMissingFile() {
    fileStorageService.delete("does-not-exist.png");
  }
}

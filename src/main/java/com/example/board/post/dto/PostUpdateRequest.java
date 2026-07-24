package com.example.board.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

// 단계 10: 이미지 개별 삭제 지원을 위해 deleteImageIds 추가.
// null 허용(안 보내면 삭제 없음). 신규 이미지는 multipart "images" 파트로 별도 수신한다.
public record PostUpdateRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank String content,
    List<Long> deleteImageIds
) {
}

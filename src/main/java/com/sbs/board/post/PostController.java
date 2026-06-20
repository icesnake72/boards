package com.sbs.board.post;

import com.sbs.board.global.IngestResult;
import com.sbs.board.post.dto.PostRequest;
import com.sbs.board.post.dto.PostDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.sbs.board.auth.AuthController.LOGIN_USER_ID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/post")
public class PostController {
    private final PostService postService;

    @PostMapping("/{boardId}/new")
    public PostDTO create(
            @PathVariable
            Long boardId,

            @SessionAttribute(name = LOGIN_USER_ID, required = false)
            Long loginUserId,

            @Valid
            @RequestBody
            PostRequest request) {
        return postService.create(boardId, loginUserId, request);
    }

    // 모든 사용자 가능
    @GetMapping("/all")
    public List<PostDTO> list() {
        return postService.list();
    }

    // id로 PostDTO 한개 반환하기, 모든 사용자 가능
    @GetMapping("/{id}")
    public PostDTO getPost(@PathVariable Long id) {
        System.out.println(id);
        return postService.getPost(id);
    }

    // update, 작성자만 가능
    @PutMapping("/{id}/update")
    public PostDTO update(
        @PathVariable Long id,

        @SessionAttribute(name = LOGIN_USER_ID, required = false)
        Long loginUserId,

        @Valid
        @RequestBody
        PostRequest request
    ) {
        return postService.update(loginUserId, id, request);
    }

    // delete, 작성자만 가능
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(
            @PathVariable Long id,

            @SessionAttribute(name = LOGIN_USER_ID, required = false)
            Long loginUserId
    ) {
        postService.delete(loginUserId, id);

        return ResponseEntity.status(HttpStatus.OK).body("ok");
    }
}

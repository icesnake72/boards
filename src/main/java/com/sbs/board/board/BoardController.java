package com.sbs.board.board;

import com.sbs.board.board.dto.BoardDTO;
import com.sbs.board.board.dto.BoardRequest;
import com.sbs.board.board.dto.BoardResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.sbs.board.auth.AuthController.LOGIN_USER_ID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/board")
public class BoardController {
    private final BoardService boardService;

    @PostMapping("/new")
    public BoardResponse create(
            @SessionAttribute(name = LOGIN_USER_ID, required = false)
            Long loginUserId,

            @Valid
            @RequestBody
            BoardRequest request) {
        return boardService.create(loginUserId, request);
    }

    @GetMapping("/all")
    public List<BoardResponse> list() {
        return boardService.list();
    }

    @PutMapping("/{id}/update")
    public BoardResponse update(
            @PathVariable Long id,

            @SessionAttribute(name = LOGIN_USER_ID, required = false)
            Long loginUserId,

            @RequestBody BoardRequest request) {

        return boardService.update(loginUserId, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(
            @PathVariable Long id,

            @SessionAttribute(name = LOGIN_USER_ID, required = false)
            Long loginUserId
    ) {
        String result = boardService.delete(loginUserId, id);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }
}

package com.sbs.board.auth;

import com.sbs.board.auth.dto.LoginRequest;
import com.sbs.board.auth.dto.SignupRequest;
import com.sbs.board.auth.dto.UserResponse;
import com.sbs.board.global.IngestResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {
    public static final String LOGIN_USER_ID = "LoginUserId";

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<IngestResult> signup(
            @Valid
            @RequestBody
            SignupRequest request) {

        IngestResult result = authService.signUp(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(
            @Valid
            @RequestBody
            LoginRequest request,
            HttpSession session) {

        UserResponse response = authService.login(request);

        // 웹서버 세션 객체에 사용자 아이디를 저장시켜둠
        session.setAttribute(LOGIN_USER_ID, response.getId());

        Long id = (Long) session.getAttribute(LOGIN_USER_ID);
        System.out.println("로그인: " + id);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        // HttpServletRequest request: Tomcat에의해 자동으로 주입되는 정보

        HttpSession session = request.getSession(false);
        if ( session != null ) {
            Long id = (Long) session.getAttribute(LOGIN_USER_ID);
            System.out.println("로그아웃 처리: " + id);
            session.invalidate();   // 세션 클리어
        }
    }
}


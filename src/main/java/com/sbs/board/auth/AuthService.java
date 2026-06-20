package com.sbs.board.auth;

import com.sbs.board.auth.dto.LoginRequest;
import com.sbs.board.auth.dto.SignupRequest;
import com.sbs.board.auth.dto.UserResponse;
import com.sbs.board.global.entity.User;
import com.sbs.board.global.entity.UserProfile;
import com.sbs.board.global.IngestResult;
import com.sbs.board.global.exception.DuplicateException;
import com.sbs.board.global.exception.NotFoundException;
import com.sbs.board.global.exception.UnauthorizedException;
import com.sbs.board.user.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.sbs.board.global.exception.ErrorCode.*;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public IngestResult signUp(SignupRequest request) {
        // signup 성공이면 IngestResult 인스턴스에 status = "ok", message = "";
        // signup 실패하면 IngestResult 인스턴스에 status = "error", message = "실패 사유";
        IngestResult result = new IngestResult();

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateException(DUPLICATE_USER_EMAIL);
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickName(request.getNickName());
        user.setRole(request.getRole().equals("ADMIN") ? User.Role.ADMIN : User.Role.USER);

        User savedUser = userRepository.save(user);

        if (!userProfileRepository.existsByUser(savedUser)) {
            // 저장된 사용자 인증정보와 매칭되는 프로필 정보도 같이 저장한다.
            UserProfile profile = new UserProfile();
            profile.setUser(savedUser);
            userProfileRepository.save( profile );
        }

        result.setStatus("ok");
        return result;
    }

    @Transactional
    public UserResponse login(LoginRequest request) {
        UserResponse response = new UserResponse();

        // request로부터 주어진 email로 데이터베이스에서 쿼리하여 User Entity를 가져온다.
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(()-> new NotFoundException(USER_NOT_FOUND));

        // 패스워드 매칭
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // 비밀번호가 일치하지 않음
            throw new UnauthorizedException(LOGIN_FAILED);
        }

        response.setId(user.getId());
        response.setEmail(user.getEmail());
        response.setNickName(user.getNickName());
        response.setRole(user.getRole().toString());

        return response;
    }

    public boolean logout() {
        return true;
    }
}

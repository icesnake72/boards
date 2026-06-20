package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

  @Mock
  UserRepository userRepository;

  @InjectMocks
  CustomUserDetailsService customUserDetailsService;

  @Test
  void should_returnUserDetailsWithRoleAuthority_whenUserExists() {
    User user = new User("tester1", "tester1@example.com", "encoded", Role.USER);
    given(userRepository.findByUsername("tester1")).willReturn(Optional.of(user));

    UserDetails userDetails = customUserDetailsService.loadUserByUsername("tester1");

    assertThat(userDetails.getUsername()).isEqualTo("tester1");
    assertThat(userDetails.getPassword()).isEqualTo("encoded");
    assertThat(userDetails.getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_USER");
  }

  @Test
  void should_throwUsernameNotFoundException_whenUserMissing() {
    given(userRepository.findByUsername("no-such-user")).willReturn(Optional.empty());

    assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("no-such-user"))
        .isInstanceOf(UsernameNotFoundException.class);
  }
}

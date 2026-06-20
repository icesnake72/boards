package com.example.board.auth;

import com.example.board.user.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// User 엔티티를 Spring Security가 이해하는 UserDetails로 감싸는 어댑터.
// 이렇게 감싸두면 인증/인가 전 과정에서 Spring 표준 메커니즘을 그대로 쓸 수 있다.
public class CustomUserDetails implements UserDetails {

  private final User user;

  public CustomUserDetails(User user) {
    this.user = user;
  }

  // 컨트롤러에서 @AuthenticationPrincipal로 받은 뒤 userId가 필요할 때 사용
  public Long getId() {
    return user.getId();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    // Spring Security의 hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN" 권한을 찾는다
    return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
  }

  @Override
  public String getPassword() {
    return user.getPassword();
  }

  @Override
  public String getUsername() {
    return user.getUsername();
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }
}

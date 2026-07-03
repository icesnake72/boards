package com.example.board.auth.oauth;

import com.example.board.auth.oauth.dto.KakaoTokenResponse;
import com.example.board.auth.oauth.dto.KakaoUserResponse;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

// 카카오 서버와의 HTTP 통신 전담 — OAuth 3단계 중 서버가 하는 두 호출(토큰 교환, 사용자 조회)을 담당한다.
// 강의 포인트: 이 클래스가 바로 spring-boot-starter-oauth2-client가 자동으로 해주는 일의 "수동 구현"이다.
// 카카오 쪽 실패 사유(만료된 code, redirect_uri 불일치 등)는 로그로 남기고,
// 클라이언트에는 OAUTH_LOGIN_FAILED(401) 하나로 응답한다 — 내부 구성 정보를 노출하지 않는다.
@Slf4j
@Component
public class KakaoOAuthClient {

  private final KakaoOAuthProperties properties;
  private final RestClient restClient;

  public KakaoOAuthClient(KakaoOAuthProperties properties, RestClient.Builder restClientBuilder) {
    this.properties = properties;
    // Boot이 자동 구성한 Builder를 쓰면 앱의 ObjectMapper·메시지 컨버터 설정을 그대로 물려받는다
    this.restClient = restClientBuilder.build();
  }

  // 1단계(인가 요청) URL — 브라우저를 이 주소로 보내면 카카오 로그인/동의 화면이 뜬다
  public String authorizeUrl(String state) {
    String uri = UriComponentsBuilder.fromUriString(properties.authorizeUri())
        .queryParam("client_id", properties.appkey())
        .queryParam("redirect_uri", properties.callback())
        .queryParam("response_type", "code")
        .queryParam("state", state)
        .build()
        .toUriString();

    log.info("카카오 인증 url: {}", uri);

    return uri;
  }

  // 2단계(토큰 교환) — 인가 코드 + client_secret으로 카카오 access token을 받는다 (서버 간 호출, 브라우저 안 거침)
  public KakaoTokenResponse requestToken(String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", properties.appkey());
    form.add("client_secret", properties.secret());
    form.add("redirect_uri", properties.callback());  // 인가 요청 때와 같은 값이어야 카카오가 승인한다
    form.add("code", code);

    try {
      KakaoTokenResponse response = restClient.post()
          .uri(properties.tokenUri())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(KakaoTokenResponse.class);
      if (response == null || response.accessToken() == null) {
        log.warn("카카오 token 응답에 access_token 없음");
        throw new UnauthorizedException(ErrorCode.OAUTH_LOGIN_FAILED);
      }
      return response;
    } catch (RestClientException e) {
      // 대표 원인: code 재사용/만료(10분), redirect_uri 불일치, client_secret 미일치
      log.warn("카카오 token 요청 실패: {}", e.getMessage());
      throw new UnauthorizedException(ErrorCode.OAUTH_LOGIN_FAILED);
    }
  }

  // 3단계(사용자 조회) — 카카오 access token으로 회원번호·닉네임·이메일을 조회한다
  public KakaoUserResponse fetchUser(String kakaoAccessToken) {
    try {
      KakaoUserResponse response = restClient.get()
          .uri(properties.userInfoUri())
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
          .retrieve()
          .body(KakaoUserResponse.class);
      if (response == null || response.id() == null) {
        log.warn("카카오 사용자 응답에 id 없음");
        throw new UnauthorizedException(ErrorCode.OAUTH_LOGIN_FAILED);
      }
      return response;
    } catch (RestClientException e) {
      log.warn("카카오 사용자 정보 조회 실패: {}", e.getMessage());
      throw new UnauthorizedException(ErrorCode.OAUTH_LOGIN_FAILED);
    }
  }
}

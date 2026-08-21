import { useState } from "react";
import { login, logout, signup } from "../api.js";

// 소셜 로그인 실패 시 백엔드가 /?error=OAUTH_LOGIN_FAILED 로 리다이렉트한다 — 한 번 읽고 URL 정리.
function consumeOauthError() {
  const err = new URLSearchParams(window.location.search).get("error");
  if (err) window.history.replaceState(null, "", window.location.pathname);
  return err ? `소셜 로그인에 실패했습니다 (${err})` : "";
}

// 소셜 로그인은 fetch가 아니라 "전체 리다이렉트"로 시작한다(제공자 동의 화면으로 이동).
// 성공하면 백엔드가 refresh 쿠키를 심고 "/"로 돌려보내고, SPA의 silent login이 세션을 복원한다.
function startSocialLogin(provider) {
  window.location.href = `/oauth2/authorization/${provider}`;
}

// 헤더의 인증 영역: 비로그인 → 로그인/회원가입 폼 + 소셜 로그인, 로그인 → 사용자표시 + 로그아웃.
export default function AuthBar({ user, onAuthed, onLoggedOut }) {
  const [mode, setMode] = useState("login");   // login | signup
  const [form, setForm] = useState({ username: "", password: "", email: "", nickname: "" });
  const [msg, setMsg] = useState(consumeOauthError);
  const [busy, setBusy] = useState(false);

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  async function handleLogin(e) {
    e.preventDefault();
    setBusy(true); setMsg("");
    try {
      await login(form.username, form.password);
      await onAuthed();                        // App이 프로필을 다시 읽어 상태 갱신
    } catch (err) {
      setMsg(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleSignup(e) {
    e.preventDefault();
    setBusy(true); setMsg("");
    try {
      await signup(form);
      setMsg("가입 완료 — 이제 로그인하세요.");
      setMode("login");
    } catch (err) {
      setMsg(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleLogout() {
    await logout();
    onLoggedOut();
  }

  if (user) {
    return (
      <div className="auth-bar">
        <span className="auth-user">{user.nickname ?? user.username ?? "사용자"} 님</span>
        <button type="button" className="btn" onClick={handleLogout}>로그아웃</button>
      </div>
    );
  }

  return (
    <div className="auth-bar">
      {mode === "login" ? (
        <form className="auth-form" onSubmit={handleLogin}>
          <input placeholder="아이디" value={form.username} onChange={set("username")} autoComplete="username" />
          <input type="password" placeholder="비밀번호" value={form.password} onChange={set("password")} autoComplete="current-password" />
          <button className="btn primary" disabled={busy}>로그인</button>
          <button type="button" className="btn" onClick={() => { setMode("signup"); setMsg(""); }}>회원가입</button>
          <button type="button" className="btn social kakao" onClick={() => startSocialLogin("kakao")}>
            {/* 카카오 말풍선 심볼(inline SVG — 외부 자산 없이 브랜드 버튼) */}
            <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
              <path fill="#191919" d="M12 3C6.48 3 2 6.54 2 10.9c0 2.8 1.86 5.25 4.64 6.65-.2.75-.74 2.72-.85 3.14-.13.52.19.51.4.37.17-.11 2.66-1.8 3.74-2.54.66.1 1.35.15 2.07.15 5.52 0 10-3.54 10-7.9S17.52 3 12 3z" />
            </svg>
            카카오 로그인
          </button>
          <button type="button" className="btn social google" onClick={() => startSocialLogin("google")}>
            {/* 구글 G 로고(inline SVG) */}
            <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
              <path fill="#4285F4" d="M23.5 12.27c0-.85-.08-1.66-.22-2.45H12v4.64h6.45c-.28 1.5-1.13 2.77-2.4 3.62v3h3.88c2.27-2.1 3.57-5.18 3.57-8.81z" />
              <path fill="#34A853" d="M12 24c3.24 0 5.96-1.07 7.94-2.91l-3.88-3c-1.08.72-2.45 1.15-4.06 1.15-3.13 0-5.78-2.11-6.72-4.95H1.28v3.1C3.25 21.3 7.31 24 12 24z" />
              <path fill="#FBBC05" d="M5.28 14.29A7.2 7.2 0 0 1 4.9 12c0-.8.14-1.57.38-2.29v-3.1H1.28A12 12 0 0 0 0 12c0 1.94.46 3.77 1.28 5.39l4-3.1z" />
              <path fill="#EA4335" d="M12 4.77c1.76 0 3.34.6 4.59 1.8l3.44-3.44C17.95 1.19 15.24 0 12 0 7.31 0 3.25 2.7 1.28 6.61l4 3.1C6.22 6.88 8.87 4.77 12 4.77z" />
            </svg>
            Google로 로그인
          </button>
        </form>
      ) : (
        <form className="auth-form" onSubmit={handleSignup}>
          <input placeholder="아이디(4자 이상)" value={form.username} onChange={set("username")} autoComplete="username" />
          <input type="email" placeholder="이메일" value={form.email} onChange={set("email")} autoComplete="email" />
          <input type="password" placeholder="비밀번호(8자 이상)" value={form.password} onChange={set("password")} autoComplete="new-password" />
          <input placeholder="닉네임" value={form.nickname} onChange={set("nickname")} />
          <button className="btn primary" disabled={busy}>가입</button>
          <button type="button" className="btn" onClick={() => { setMode("login"); setMsg(""); }}>취소</button>
        </form>
      )}
      {msg && <span className="auth-msg">{msg}</span>}
    </div>
  );
}

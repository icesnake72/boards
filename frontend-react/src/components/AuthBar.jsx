import { useState } from "react";
import { login, logout, signup } from "../api.js";

// 헤더의 인증 영역: 비로그인 → 로그인/회원가입 폼, 로그인 → 사용자표시 + 로그아웃.
export default function AuthBar({ user, onAuthed, onLoggedOut }) {
  const [mode, setMode] = useState("login");   // login | signup
  const [form, setForm] = useState({ username: "", password: "", email: "", nickname: "" });
  const [msg, setMsg] = useState("");
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

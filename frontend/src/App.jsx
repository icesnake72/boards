import { useCallback, useEffect, useState } from "react";
import { getMe, silentLogin } from "./api.js";
import AuthBar from "./components/AuthBar.jsx";
import Boards from "./components/Boards.jsx";
import Posts from "./components/Posts.jsx";
import PostDetail from "./components/PostDetail.jsx";

// 화면 전환은 라우터 없이 상태로 처리한다(배포 실습용 최소 구조).
//   boards(게시판 목록) → posts(글 목록/작성) → post(글 상세/댓글/반응)
export default function App() {
  const [user, setUser] = useState(null);       // 로그인 사용자(프로필) 또는 null
  const [ready, setReady] = useState(false);    // silent login 시도 완료 여부
  const [view, setView] = useState({ name: "boards" });

  // 로그인 성공/세션 복원 후 프로필을 읽어 상태 반영
  const refreshUser = useCallback(async () => {
    try {
      setUser(await getMe());
    } catch {
      setUser(null);
    }
  }, []);

  // 앱 시작 시 refresh 쿠키로 세션 복원(access는 메모리라 새로고침에 사라지므로)
  useEffect(() => {
    (async () => {
      if (await silentLogin()) await refreshUser();
      setReady(true);
    })();
  }, [refreshUser]);

  return (
    <>
      <header className="site-header">
        <div className="wrap header-row">
          <div>
            <h1 className="clickable" onClick={() => setView({ name: "boards" })}>게시판</h1>
            <p className="subtitle">React(Vite) + Nginx 리버스 프록시 — 로그인·글·댓글·반응까지 백엔드 연동 테스트</p>
          </div>
          {ready && (
            <AuthBar user={user} onAuthed={refreshUser} onLoggedOut={() => setUser(null)} />
          )}
        </div>
      </header>

      <main className="wrap">
        {view.name === "boards" && (
          <Boards user={user} onOpenBoard={(board) => setView({ name: "posts", board })} />
        )}
        {view.name === "posts" && (
          <Posts board={view.board} user={user}
            onOpenPost={(p) => setView({ name: "post", postId: p.id, board: view.board })}
            onBack={() => setView({ name: "boards" })} />
        )}
        {view.name === "post" && (
          <PostDetail postId={view.postId} user={user}
            onBack={() => setView({ name: "posts", board: view.board })} />
        )}
      </main>

      <footer className="site-footer">
        <div className="wrap">
          <span>
            인증: <code>Bearer(메모리) + httpOnly refresh 쿠키</code> · 401이면 자동 재발급 후 재시도
          </span>
        </div>
      </footer>
    </>
  );
}

import { useCallback, useEffect, useState } from "react";

// 순수 JS 버전(frontend/app.js)과 구현 동일: 게시판 목록만 불러와 렌더.
// 상대경로라 Nginx 리버스 프록시가 백엔드로 넘겨준다(같은 origin → CORS 불필요).
const API_BOARDS = "/api/v1/boards";

// 서버 LocalDateTime 문자열을 읽기 좋게
function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export default function App() {
  const [boards, setBoards] = useState([]);
  const [status, setStatus] = useState("불러오는 중…");
  const [isError, setIsError] = useState(false);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setStatus("불러오는 중…");
    setIsError(false);
    setLoading(true);
    try {
      const res = await fetch(API_BOARDS, { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error(`서버 응답 오류 (HTTP ${res.status})`);
      const data = await res.json();
      if (!Array.isArray(data) || data.length === 0) {
        setBoards([]);
        setStatus("등록된 게시판이 없습니다.");
        return;
      }
      setBoards(data);
      setStatus("");
    } catch (err) {
      setBoards([]);
      setStatus(`목록을 불러오지 못했습니다: ${err.message}`);
      setIsError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <>
      <header className="site-header">
        <div className="wrap">
          <h1>게시판</h1>
          <p className="subtitle">React(Vite) 프론트 + Nginx 리버스 프록시 — 배포 실습용 최소 화면</p>
        </div>
      </header>

      <main className="wrap">
        <div className="toolbar">
          <span className="count" aria-live="polite">
            {boards.length > 0 ? `${boards.length}개` : ""}
          </span>
          <button type="button" className="btn" onClick={load} disabled={loading}>
            새로고침
          </button>
        </div>

        {status && (
          <div className={`status${isError ? " error" : ""}`} role="status">
            {status}
          </div>
        )}

        {/* React는 {board.name} 을 기본 이스케이프하므로 XSS 안전 */}
        <ul className="board-list">
          {boards.map((b) => (
            <li key={b.id} className="board-card">
              <span className="board-id">#{b.id}</span>
              <div className="board-body">
                <p className="board-name">{b.name ?? "(이름 없음)"}</p>
                <p className="board-desc">{b.description ?? ""}</p>
              </div>
              <time className="board-date" dateTime={b.createdAt ?? ""}>
                {formatDate(b.createdAt)}
              </time>
            </li>
          ))}
        </ul>
      </main>

      <footer className="site-footer">
        <div className="wrap">
          <span>
            데이터 출처: <code>GET /api/v1/boards</code> (Nginx가 백엔드로 프록시)
          </span>
        </div>
      </footer>
    </>
  );
}

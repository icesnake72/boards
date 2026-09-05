import { useEffect, useState } from "react";
import { createBoard, getBoards } from "../api.js";

// 게시판 목록 + (관리자용) 게시판 생성. 일반 사용자가 생성을 누르면 403이 나는데,
// 그 자체가 @PreAuthorize("hasRole('ADMIN')") 인가를 눈으로 확인하는 백엔드 테스트다.
export default function Boards({ user, onOpenBoard }) {
  const [boards, setBoards] = useState([]);
  const [status, setStatus] = useState("불러오는 중…");
  const [form, setForm] = useState({ name: "", description: "" });
  const [msg, setMsg] = useState("");

  async function load() {
    setStatus("불러오는 중…");
    try {
      const data = await getBoards();
      setBoards(data);
      setStatus(data.length === 0 ? "등록된 게시판이 없습니다." : "");
    } catch (err) {
      setStatus(`목록을 불러오지 못했습니다: ${err.message}`);
    }
  }

  useEffect(() => { load(); }, []);

  async function handleCreate(e) {
    e.preventDefault();
    setMsg("");
    try {
      await createBoard(form.name, form.description);
      setForm({ name: "", description: "" });
      setMsg("게시판이 생성되었습니다.");
      load();
    } catch (err) {
      setMsg(err.message);              // 일반 USER면 403 ACCESS_DENIED가 표시된다
    }
  }

  return (
    <section>
      <div className="toolbar">
        <span className="count">{boards.length > 0 ? `${boards.length}개` : ""}</span>
        <button type="button" className="btn" onClick={load}>새로고침</button>
      </div>

      {status && <div className="status" role="status">{status}</div>}

      <ul className="board-list">
        {boards.map((b) => (
          <li key={b.id} className="board-card clickable" onClick={() => onOpenBoard(b)}>
            <span className="board-id">#{b.id}</span>
            <div className="board-body">
              <p className="board-name">{b.name}</p>
              <p className="board-desc">{b.description ?? ""}</p>
            </div>
            <span className="chevron">›</span>
          </li>
        ))}
      </ul>

      {user && (
        <form className="inline-form" onSubmit={handleCreate}>
          <strong>게시판 생성(관리자 전용 — 일반 계정은 403)</strong>
          <div className="row">
            <input placeholder="이름" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <input placeholder="설명" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            <button className="btn primary">생성</button>
          </div>
          {msg && <span className="form-msg">{msg}</span>}
        </form>
      )}
    </section>
  );
}

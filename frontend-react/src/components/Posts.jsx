import { useEffect, useState } from "react";
import { createPost, getPosts } from "../api.js";

function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// 한 게시판의 글 목록 + 글 작성(multipart: 제목/내용 + 이미지 선택).
export default function Posts({ board, user, onOpenPost, onBack }) {
  const [page, setPage] = useState(null);      // Page<PostListResponse>
  const [status, setStatus] = useState("불러오는 중…");
  const [form, setForm] = useState({ title: "", content: "" });
  const [files, setFiles] = useState([]);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);

  async function load() {
    setStatus("불러오는 중…");
    try {
      const data = await getPosts(board.id);
      setPage(data);
      setStatus(data.content.length === 0 ? "아직 글이 없습니다." : "");
    } catch (err) {
      setStatus(`글 목록을 불러오지 못했습니다: ${err.message}`);
    }
  }

  useEffect(() => { load(); }, [board.id]);

  async function handleCreate(e) {
    e.preventDefault();
    setBusy(true); setMsg("");
    try {
      const created = await createPost(board.id, form, files);
      setForm({ title: "", content: "" });
      setFiles([]);
      e.target.reset?.();
      setMsg(`글이 등록되었습니다 (#${created.id}).`);
      load();
    } catch (err) {
      setMsg(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <div className="toolbar">
        <button type="button" className="btn" onClick={onBack}>← 게시판 목록</button>
        <h2 className="section-title">{board.name}</h2>
        <button type="button" className="btn" onClick={load}>새로고침</button>
      </div>

      {status && <div className="status" role="status">{status}</div>}

      <ul className="post-list">
        {page?.content.map((p) => (
          <li key={p.id} className="post-row clickable" onClick={() => onOpenPost(p)}>
            {p.thumbnailUrl && <img className="thumb" src={p.thumbnailUrl} alt="" />}
            <div className="post-row-body">
              <p className="post-title">{p.title}</p>
              <p className="post-meta">{p.authorUsername} · 조회 {p.viewCount} · {formatDate(p.createdAt)}</p>
            </div>
            <span className="chevron">›</span>
          </li>
        ))}
      </ul>

      {user ? (
        <form className="inline-form" onSubmit={handleCreate}>
          <strong>글 쓰기</strong>
          <input placeholder="제목" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
          <textarea rows="4" placeholder="내용" value={form.content} onChange={(e) => setForm({ ...form, content: e.target.value })} />
          <div className="row">
            <input type="file" accept="image/*" multiple onChange={(e) => setFiles([...e.target.files])} />
            <button className="btn primary" disabled={busy}>등록</button>
          </div>
          {msg && <span className="form-msg">{msg}</span>}
        </form>
      ) : (
        <div className="status">글을 쓰려면 로그인하세요.</div>
      )}
    </section>
  );
}

import { useCallback, useEffect, useRef, useState } from "react";
import { createPost, getPostsByCursor } from "../api.js";

function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

const PAGE_SIZE = 20;

// 한 게시판의 글 목록(무한스크롤) + 글 작성(multipart: 제목/내용 + 이미지 선택).
// 단계 16 처리에 의해 변경 — offset(Page) 방식에서 keyset(cursor) 방식으로 전환.
//   목록 하단의 센티널(빈 div)이 화면에 들어오면 IntersectionObserver가 다음 페이지를
//   이어 붙인다. 서버 커서(lastCreatedAt, lastId)를 그대로 되돌려 보내는 것이 전부라서
//   프론트는 "몇 페이지째인지"를 계산할 필요가 없다.
export default function Posts({ board, user, onOpenPost, onBack }) {
  const [items, setItems] = useState([]);          // 지금까지 이어 붙인 글 목록
  const [hasNext, setHasNext] = useState(false);
  const [status, setStatus] = useState("불러오는 중…");
  const [form, setForm] = useState({ title: "", content: "" });
  const [files, setFiles] = useState([]);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const sentinelRef = useRef(null);                // 목록 끝 감지용 센티널
  const loadingRef = useRef(false);                // 중복 로드 방지(관찰 콜백은 연달아 올 수 있다)
  // 커서는 ref로 보관 — observer 콜백은 등록 시점의 클로저를 계속 쓰므로
  // state에 두면 낡은 커서를 보낼 수 있다. 렌더에 쓰는 값이 아니라 ref가 적합하다.
  const cursorRef = useRef(null);                  // { lastCreatedAt, lastId } | null

  // reset=true면 처음부터(첫 페이지), false면 현재 커서에서 다음 페이지를 이어 붙인다.
  const load = useCallback(async (reset) => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    if (reset) setStatus("불러오는 중…");
    try {
      const data = await getPostsByCursor(board.id, reset ? null : cursorRef.current, PAGE_SIZE);
      setItems((prev) => (reset ? data.items : [...prev, ...data.items]));
      cursorRef.current = data.hasNext
        ? { lastCreatedAt: data.lastCreatedAt, lastId: data.lastId }
        : null;
      setHasNext(data.hasNext);
      setStatus(reset && data.items.length === 0 ? "아직 글이 없습니다." : "");
    } catch (err) {
      setStatus(`글 목록을 불러오지 못했습니다: ${err.message}`);
    } finally {
      loadingRef.current = false;
    }
  }, [board.id]);

  useEffect(() => {
    cursorRef.current = null;
    load(true);
  }, [board.id, load]);

  // 센티널이 뷰포트에 들어오면 다음 페이지 로드. hasNext가 없으면 관찰하지 않는다.
  useEffect(() => {
    if (!hasNext) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting) load(false); },
      { rootMargin: "200px" }          // 바닥 200px 전에 미리 당겨와 끊김을 줄인다
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasNext, load]);

  async function handleCreate(e) {
    e.preventDefault();
    setBusy(true); setMsg("");
    try {
      const created = await createPost(board.id, form, files);
      setForm({ title: "", content: "" });
      setFiles([]);
      e.target.reset?.();
      setMsg(`글이 등록되었습니다 (#${created.id}).`);
      load(true);                      // 새 글은 맨 위에 오므로 처음부터 다시
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
        <button type="button" className="btn" onClick={() => load(true)}>새로고침</button>
      </div>

      {status && <div className="status" role="status">{status}</div>}

      <ul className="post-list">
        {items.map((p) => (
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

      {/* 무한스크롤 센티널 — hasNext일 때만 존재. 화면에 들어오면 다음 페이지를 당긴다 */}
      {hasNext && <div ref={sentinelRef} className="status">더 불러오는 중…</div>}

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

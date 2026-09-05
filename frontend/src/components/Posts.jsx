import { useCallback, useEffect, useRef, useState } from "react";
import { createPost, getPosts, getPostsByCursor, searchPosts } from "../api.js";

function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

const PAGE_SIZE = 20;

// 페이지네이션 바에 보여줄 번호 목록: [1] … [c-2..c+2] … [last] (0은 말줄임 자리)
function pageWindow(current, total) {
  if (total <= 9) return Array.from({ length: total }, (_, i) => i + 1);
  const around = [];
  for (let n = Math.max(1, current - 2); n <= Math.min(total, current + 2); n++) around.push(n);
  const result = [];
  if (around[0] > 1) result.push(1);
  if (around[0] > 2) result.push(0);
  result.push(...around);
  if (around[around.length - 1] < total - 1) result.push(0);
  if (around[around.length - 1] < total) result.push(total);
  return result;
}

// 한 게시판의 글 목록 + 글 작성(multipart: 제목/내용 + 이미지 선택).
// 페이지네이션은 하이브리드 — 두 API가 각자 잘하는 일을 맡는다:
//   - 페이지 점프(번호 클릭): offset API — "N번째 페이지"는 위치 기반 질의라 offset의 몫
//   - 이어 보기(무한스크롤): keyset(cursor) API — 깊어져도 느려지지 않는 단계 16 방식
// 목록은 페이지 블록 단위로 렌더하고, 각 블록을 IntersectionObserver로 관찰해
// 스크롤 위치에 따라 하단 바의 현재 페이지 번호가 자연스럽게 따라온다.
export default function Posts({ board, user, onOpenPost, onBack }) {
  const [pageBlocks, setPageBlocks] = useState([]);  // [{ no, items }] — 연속 구간
  const [totalPages, setTotalPages] = useState(0);
  const [currentPage, setCurrentPage] = useState(1); // 스크롤/점프에 따라 갱신 (1-based)
  const [hasNext, setHasNext] = useState(false);
  const [status, setStatus] = useState("불러오는 중…");
  const [form, setForm] = useState({ title: "", content: "" });
  const [files, setFiles] = useState([]);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState(false);
  const [showTop, setShowTop] = useState(false);     // 최상단 버튼 노출 여부
  const [searchInput, setSearchInput] = useState(""); // 검색창 입력값
  const [activeQuery, setActiveQuery] = useState(""); // 제출된 검색어 ("" = 일반 목록 모드)
  const sentinelRef = useRef(null);                  // 목록 끝 감지용 센티널
  const loadingRef = useRef(false);                  // 중복 로드 방지
  // 커서는 ref로 보관 — observer 콜백의 클로저가 낡은 state를 참조하는 함정 회피
  const cursorRef = useRef(null);                    // { lastCreatedAt, lastId } | null
  const blockRefs = useRef(new Map());               // pageNo → 블록 DOM (현재 페이지 감지용)
  // 세대 번호 — 점프가 일어나면 +1. 이전 세대의 이어 보기 응답은 도착해도 폐기해서
  // "점프 결과 위에 옛 스크롤 응답이 덧붙는" 경쟁을 막는다.
  const genRef = useRef(0);
  // 검색어도 observer 콜백에서 읽으므로 ref로 함께 보관(stale closure 회피)
  const activeQueryRef = useRef("");

  // 페이지 점프: offset API로 해당 페이지를 새로 그린다(전체 페이지 수도 이때 갱신).
  // 진행 중인 이어 보기(loadMore)가 있어도 기다리지 않는다 — 세대 가드가 정리한다.
  const jumpToPage = useCallback(async (pageNo, { scrollTop = true } = {}) => {
    const gen = ++genRef.current;
    setStatus("불러오는 중…");
    try {
      const data = await getPosts(board.id, pageNo - 1, PAGE_SIZE);
      if (genRef.current !== gen) return;            // 더 새로운 점프가 끼어들었으면 폐기
      const items = data.content;
      setPageBlocks([{ no: pageNo, items }]);
      setTotalPages(data.page.totalPages);
      setCurrentPage(pageNo);
      // 이 페이지 마지막 행이 keyset 이어 보기의 커서가 된다(정렬 기준이 동일).
      const last = items[items.length - 1];
      cursorRef.current = last ? { lastCreatedAt: last.createdAt, lastId: last.id } : null;
      setHasNext(pageNo < data.page.totalPages);
      setStatus(items.length === 0 ? "아직 글이 없습니다." : "");
      if (scrollTop) window.scrollTo({ top: 0 });
    } catch (err) {
      if (genRef.current === gen) setStatus(`글 목록을 불러오지 못했습니다: ${err.message}`);
    }
  }, [board.id]);

  // 단계 17: 검색 첫 페이지 — 목록을 검색 결과로 교체하고 커서를 잇는다.
  // 검색 모드에서는 페이지 바를 숨긴다(totalPages=0) — 검색 API는 COUNT를 세지
  // 않으므로 "몇 페이지 중 몇 번째"라는 개념 자체가 없다. 무한스크롤만 남는다.
  const searchFirst = useCallback(async (query) => {
    const gen = ++genRef.current;
    setStatus("검색 중…");
    try {
      const data = await searchPosts(board.id, query, null, PAGE_SIZE);
      if (genRef.current !== gen) return;
      setPageBlocks([{ no: 1, items: data.items }]);
      setTotalPages(0);
      setCurrentPage(1);
      cursorRef.current = data.hasNext
        ? { lastCreatedAt: data.lastCreatedAt, lastId: data.lastId }
        : null;
      setHasNext(data.hasNext);
      setStatus(data.items.length === 0 ? "검색 결과가 없습니다." : "");
      window.scrollTo({ top: 0 });
    } catch (err) {
      // 2글자 미만(400) 등 서버 검증 메시지를 그대로 보여준다
      if (genRef.current === gen) setStatus(err.message);
    }
  }, [board.id]);

  // 이어 보기: 커서 이후를 받아 다음 번호의 블록으로 붙인다.
  // 일반 모드는 keyset cursor API, 검색 모드는 search API — 커서 계약이 같아
  // 이 함수 하나가 두 모드를 겸한다.
  const loadMore = useCallback(async () => {
    if (loadingRef.current || !cursorRef.current) return;
    loadingRef.current = true;
    const gen = genRef.current;
    try {
      const cursor = cursorRef.current;
      const data = activeQueryRef.current
        ? await searchPosts(board.id, activeQueryRef.current, cursor, PAGE_SIZE)
        : await getPostsByCursor(board.id, cursor, PAGE_SIZE);
      if (genRef.current !== gen) return;            // 응답 대기 중 점프 발생 — 이 결과는 버린다
      setPageBlocks((prev) => {
        const nextNo = prev.length ? prev[prev.length - 1].no + 1 : 1;
        return [...prev, { no: nextNo, items: data.items }];
      });
      cursorRef.current = data.hasNext
        ? { lastCreatedAt: data.lastCreatedAt, lastId: data.lastId }
        : null;
      setHasNext(data.hasNext);
    } catch (err) {
      if (genRef.current === gen) setStatus(`글 목록을 불러오지 못했습니다: ${err.message}`);
    } finally {
      loadingRef.current = false;
    }
  }, [board.id]);

  useEffect(() => {
    // 게시판이 바뀌면 검색 모드도 해제하고 처음부터
    activeQueryRef.current = "";
    setActiveQuery("");
    setSearchInput("");
    jumpToPage(1, { scrollTop: false });
  }, [jumpToPage]);

  function handleSearch(e) {
    e.preventDefault();
    const q = searchInput.trim();
    if (!q) return;
    activeQueryRef.current = q;
    setActiveQuery(q);
    searchFirst(q);
  }

  function clearSearch() {
    activeQueryRef.current = "";
    setActiveQuery("");
    setSearchInput("");
    jumpToPage(1);
  }

  // 센티널이 뷰포트에 들어오면 다음 페이지 로드(바닥 200px 전에 미리 당긴다).
  useEffect(() => {
    if (!hasNext) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting) loadMore(); },
      { rootMargin: "200px" }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasNext, loadMore]);

  // 스크롤에 따라 현재 페이지 갱신: 뷰포트 상단 30% 지점(앵커)을 지나 있는
  // 마지막 블록이 "현재 페이지". offsetTop 비교라 로드·리렌더 타이밍과 무관하게 결정적이다.
  useEffect(() => {
    const update = () => {
      const anchor = window.scrollY + window.innerHeight * 0.3;
      let cur = pageBlocks.length ? pageBlocks[0].no : 1;
      for (const block of pageBlocks) {
        const el = blockRefs.current.get(block.no);
        if (el && el.offsetTop <= anchor) cur = block.no;
      }
      setCurrentPage(cur);
    };
    update();
    window.addEventListener("scroll", update, { passive: true });
    return () => window.removeEventListener("scroll", update);
  }, [pageBlocks]);

  // 최상단 버튼: 한 화면 이상 내려갔을 때만 보여준다.
  useEffect(() => {
    const onScroll = () => setShowTop(window.scrollY > 400);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  async function handleCreate(e) {
    e.preventDefault();
    setBusy(true); setMsg("");
    try {
      const created = await createPost(board.id, form, files);
      setForm({ title: "", content: "" });
      setFiles([]);
      e.target.reset?.();
      setMsg(`글이 등록되었습니다 (#${created.id}).`);
      jumpToPage(1);                   // 새 글은 맨 위 — 1페이지부터 다시
    } catch (err) {
      setMsg(err.message);
    } finally {
      setBusy(false);
    }
  }

  const loadedNos = pageBlocks.map((b) => b.no);

  return (
    <section>
      <div className="toolbar">
        <button type="button" className="btn" onClick={onBack}>← 게시판 목록</button>
        <h2 className="section-title">{board.name}</h2>
        <button type="button" className="btn" onClick={() => (activeQuery ? searchFirst(activeQuery) : jumpToPage(1))}>새로고침</button>
      </div>

      {/* 단계 17: 게시판 내 검색 — 제출 시 목록이 검색 결과로 바뀐다 */}
      <form className="search-bar" onSubmit={handleSearch}>
        <input
          placeholder="제목·내용 검색 (2글자 이상)"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />
        <button className="btn primary">검색</button>
        {activeQuery && (
          <button type="button" className="btn" onClick={clearSearch}>해제</button>
        )}
      </form>
      {activeQuery && <div className="status">“{activeQuery}” 검색 결과 (최신순)</div>}

      {status && <div className="status" role="status">{status}</div>}

      {pageBlocks.map((block) => (
        <ul
          key={block.no}
          className="post-list"
          data-page={block.no}
          ref={(el) => {
            if (el) blockRefs.current.set(block.no, el);
            else blockRefs.current.delete(block.no);
          }}
        >
          {block.items.map((p) => (
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
      ))}

      {/* 무한스크롤 센티널 — hasNext일 때만 존재. 화면에 들어오면 다음 페이지를 당긴다 */}
      {hasNext && <div ref={sentinelRef} className="status">더 불러오는 중…</div>}

      {/* 페이지 점프 바 — 현재 페이지는 스크롤을 따라온다. 클릭하면 offset API로 점프 */}
      {totalPages > 1 && (
        <nav className="pagination" aria-label="페이지 이동">
          {pageWindow(currentPage, totalPages).map((n, i) =>
            n === 0 ? (
              <span key={`gap-${i}`} className="page-gap">…</span>
            ) : (
              <button
                type="button"
                key={n}
                className={`page-btn${n === currentPage ? " active" : ""}`}
                onClick={() => {
                  // 이미 이어 붙어 있는 블록이면 그 위치로 스크롤, 아니면 offset 점프
                  if (loadedNos.includes(n)) {
                    blockRefs.current.get(n)?.scrollIntoView({ behavior: "smooth" });
                  } else {
                    jumpToPage(n);
                  }
                }}
              >
                {n}
              </button>
            )
          )}
        </nav>
      )}

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

      {/* 최상단 이동 — 한 화면 이상 내려가면 우하단에 나타난다 */}
      {showTop && (
        <button
          type="button"
          className="top-btn"
          aria-label="맨 위로"
          onClick={() => window.scrollTo({ top: 0, behavior: "smooth" })}
        >
          ↑
        </button>
      )}
    </section>
  );
}

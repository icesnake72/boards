// 백엔드 API 클라이언트 — 인증 토큰 관리 + 401 자동 재발급(fetch 래퍼).
//
// 토큰 정책(단계 5와 짝):
//   - access token: 응답 본문으로 받아 "메모리"에만 보관(XSS로 털릴 localStorage 회피)
//   - refresh token: httpOnly 쿠키(JS가 못 읽음) — reissue/logout 때 브라우저가 자동 전송
// 메모리 보관이라 새로고침하면 access가 사라지므로, 앱 시작 시 silentLogin()으로
// reissue를 한 번 호출해 세션을 복원한다(쿠키가 살아 있으면 로그인 유지).

let accessToken = null;

export function isLoggedIn() {
  return accessToken != null;
}

// 에러 응답({code, message})을 Error로 변환 — 화면에서 err.message로 표시
async function toError(res) {
  let msg = `HTTP ${res.status}`;
  try {
    const body = await res.json();
    if (body.message) msg = `${body.message} (${body.code ?? res.status})`;
  } catch { /* 본문이 JSON이 아니면 상태코드만 */ }
  const err = new Error(msg);
  err.status = res.status;
  return err;
}

// refresh 쿠키로 access token 재발급. 성공 시 true.
async function reissue() {
  const res = await fetch("/api/v1/auth/reissue", {
    method: "POST",
    credentials: "include",          // httpOnly refresh 쿠키를 실어 보낸다
  });
  if (!res.ok) {
    accessToken = null;
    return false;
  }
  const data = await res.json();
  accessToken = data.accessToken;
  return true;
}

// 모든 API 호출의 공통 관문: Bearer 부착 + 401이면 reissue 후 1회 재시도.
export async function authFetch(url, options = {}) {
  const doFetch = () =>
    fetch(url, {
      ...options,
      credentials: "include",
      headers: {
        ...(options.headers ?? {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
    });
  let res = await doFetch();
  if (res.status === 401 && (await reissue())) {
    res = await doFetch();           // 새 access로 원요청 재시도
  }
  return res;
}

// JSON 요청/응답 헬퍼 — 실패 시 서버 메시지를 담은 Error를 던진다
async function jsonFetch(url, options = {}) {
  const res = await authFetch(url, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers ?? {}) },
  });
  if (!res.ok) throw await toError(res);
  return res.status === 204 ? null : res.json();
}

// ── 인증 ────────────────────────────────────────────────────────────────────
export async function signup({ username, email, password, nickname }) {
  return jsonFetch("/api/v1/auth/signup", {
    method: "POST",
    body: JSON.stringify({ username, email, password, nickname }),
  });
}

export async function login(username, password) {
  const data = await jsonFetch("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
  accessToken = data.accessToken;    // 본문의 access는 메모리에, refresh는 쿠키로 이미 저장됨
  return data;
}

export async function logout() {
  try {
    await authFetch("/api/v1/auth/logout", { method: "POST" });
  } finally {
    accessToken = null;              // 서버 실패와 무관하게 로컬 세션은 종료
  }
}

// 새로고침 후 세션 복원: refresh 쿠키가 살아 있으면 로그인 상태로 복귀
export async function silentLogin() {
  return reissue();
}

export async function getMe() {
  return jsonFetch("/api/v1/profiles/me");
}

// ── 게시판 ──────────────────────────────────────────────────────────────────
export async function getBoards() {
  return jsonFetch("/api/v1/boards");
}

export async function createBoard(name, description) {
  return jsonFetch("/api/v1/boards", {
    method: "POST",
    body: JSON.stringify({ name, description }),
  });
}

// ── 게시글 ──────────────────────────────────────────────────────────────────
export async function getPosts(boardId, page = 0, size = 20) {
  return jsonFetch(`/api/v1/boards/${boardId}/posts?page=${page}&size=${size}`);
}

// 단계 16: keyset(cursor) 목록 — 무한스크롤용.
// cursor는 직전 응답의 { lastCreatedAt, lastId } 그대로. 첫 페이지는 null.
export async function getPostsByCursor(boardId, cursor = null, size = 20) {
  const params = new URLSearchParams({ size });
  if (cursor) {
    params.set("lastCreatedAt", cursor.lastCreatedAt);
    params.set("lastId", cursor.lastId);
  }
  return jsonFetch(`/api/v1/boards/${boardId}/posts/cursor?${params}`);
}

export async function getPost(id) {
  return jsonFetch(`/api/v1/posts/${id}`);
}

// 글 작성은 multipart: "post" 파트(JSON) + "images" 파트(파일들, 선택)
export async function createPost(boardId, { title, content }, files = []) {
  const form = new FormData();
  form.append("post", new Blob([JSON.stringify({ title, content })], { type: "application/json" }));
  for (const f of files) form.append("images", f);
  const res = await authFetch(`/api/v1/boards/${boardId}/posts`, {
    method: "POST",
    body: form,                      // Content-Type은 브라우저가 boundary와 함께 설정
  });
  if (!res.ok) throw await toError(res);
  return res.json();
}

export async function deletePost(id) {
  return jsonFetch(`/api/v1/posts/${id}`, { method: "DELETE" });
}

// ── 댓글 ────────────────────────────────────────────────────────────────────
export async function getComments(postId, page = 0, size = 50) {
  return jsonFetch(`/api/v1/posts/${postId}/comments?page=${page}&size=${size}`);
}

// parentId가 null이면 최상위 댓글, 있으면 그 댓글의 대댓글(1단계)
export async function createComment(postId, content, parentId = null) {
  return jsonFetch(`/api/v1/posts/${postId}/comments`, {
    method: "POST",
    body: JSON.stringify({ content, parentId }),
  });
}

export async function deleteComment(id) {
  return jsonFetch(`/api/v1/comments/${id}`, { method: "DELETE" });
}

// ── 반응(좋아요/싫어요) — 같은 타입 재요청 시 토글 취소, 다른 타입이면 전환 ──
export async function reactToPost(postId, type) {
  return jsonFetch(`/api/v1/posts/${postId}/reactions`, {
    method: "POST",
    body: JSON.stringify({ type }),
  });
}

export async function reactToComment(commentId, type) {
  return jsonFetch(`/api/v1/comments/${commentId}/reactions`, {
    method: "POST",
    body: JSON.stringify({ type }),
  });
}

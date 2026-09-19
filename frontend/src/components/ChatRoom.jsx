import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { deleteChatRoom, getChatMembers, getChatMessages, leaveChatRoom } from "../api.js";
import { chatSocket } from "../chatSocket.js";

function formatTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

// 방 화면: 이력(keyset, 위로 더 보기) + 실시간 수신(/topic/rooms/{id}) + 전송(/app/…).
// 구독하는 순간 서버가 ENTER 시스템 메시지를 만들고, 구독 해제(화면 이탈)가 LEAVE가 된다.
export default function ChatRoom({ room, user, connState, onBack }) {
  const [messages, setMessages] = useState([]);
  const [hasMore, setHasMore] = useState(false);
  const [nextBefore, setNextBefore] = useState(null);
  const [members, setMembers] = useState([]);
  const [status, setStatus] = useState("불러오는 중…");
  const [input, setInput] = useState("");
  const [msg, setMsg] = useState("");

  const listRef = useRef(null);
  // 렌더 후 스크롤 처리 지시: "bottom"(아래 고정) | {prevHeight}(위로 더 보기 위치 유지)
  const scrollPlanRef = useRef(null);
  const mine = (m) => user && m.senderUsername === user.username;
  const isOwner = user && room.ownerUsername === user.username;

  // 수신 메시지 추가 — 중복(재구독 직후 등)은 id로 걸러낸다
  function append(message) {
    setMessages((prev) => {
      if (prev.some((m) => m.id === message.id)) return prev;
      const el = listRef.current;
      const nearBottom =
        el && el.scrollHeight - el.scrollTop - el.clientHeight < 120;
      if (nearBottom || message.senderUsername === user?.username) {
        scrollPlanRef.current = "bottom";
      }
      return [...prev, message];
    });
  }

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const [page, memberList] = await Promise.all([
          getChatMessages(room.id),
          getChatMembers(room.id),
        ]);
        if (!alive) return;
        setMessages(page.messages);
        setHasMore(page.hasMore);
        setNextBefore(page.nextBefore);
        setMembers(memberList);
        setStatus("");
        scrollPlanRef.current = "bottom";
      } catch (err) {
        if (alive) setStatus(`불러오지 못했습니다: ${err.message}`);
      }
    })();

    // 방 브로드캐스트 구독 — ENTER/LEAVE가 오면 접속자 목록도 최신화한다
    const unsubRoom = chatSocket.subscribeJson(`/topic/rooms/${room.id}`, (message) => {
      append(message);
      if (message.type === "ENTER" || message.type === "LEAVE") {
        getChatMembers(room.id).then(setMembers).catch(() => {});
      }
    });
    // 전송 거부(멤버 아님, 1000자 초과 등)는 연결 유지 채로 개인 큐로 온다
    const unsubErrors = chatSocket.subscribeJson("/user/queue/errors", (error) => {
      setMsg(`${error.message} (${error.code})`);
    });
    return () => {
      alive = false;
      unsubRoom();          // UNSUBSCRIBE → 마지막 세션이면 서버가 LEAVE 처리
      unsubErrors();
    };
  }, [room.id]);           // eslint-disable-line react-hooks/exhaustive-deps

  // 스크롤 계획 실행: 새 메시지는 아래로, "더 보기"는 보던 위치 유지
  useLayoutEffect(() => {
    const el = listRef.current;
    const plan = scrollPlanRef.current;
    if (!el || !plan) return;
    if (plan === "bottom") {
      el.scrollTop = el.scrollHeight;
    } else if (plan.prevHeight != null) {
      el.scrollTop += el.scrollHeight - plan.prevHeight;
    }
    scrollPlanRef.current = null;
  }, [messages]);

  async function loadOlder() {
    try {
      const page = await getChatMessages(room.id, nextBefore);
      scrollPlanRef.current = { prevHeight: listRef.current?.scrollHeight };
      setMessages((prev) => [...page.messages, ...prev]);
      setHasMore(page.hasMore);
      setNextBefore(page.nextBefore);
    } catch (err) {
      setMsg(err.message);
    }
  }

  function handleSend(e) {
    e.preventDefault();
    const content = input.trim();
    if (!content) return;
    if (content.length > 1000) {
      setMsg("메시지는 1000자 이하여야 합니다.");
      return;
    }
    setMsg("");
    try {
      chatSocket.sendMessage(room.id, content);   // 브로드캐스트로 돌아와 append된다
      setInput("");
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function handleLeave() {
    try {
      await leaveChatRoom(room.id);
      onBack();
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function handleDelete() {
    try {
      await deleteChatRoom(room.id);
      onBack();
    } catch (err) {
      setMsg(err.message);
    }
  }

  return (
    <section>
      <div className="toolbar">
        <button type="button" className="btn" onClick={onBack}>← 로비</button>
        <div className="row">
          {isOwner
            ? <button type="button" className="btn danger" onClick={handleDelete}>방 삭제</button>
            : <button type="button" className="btn" onClick={handleLeave}>나가기</button>}
        </div>
      </div>

      <div className="chat-panel">
        <div className="chat-head">
          <div>
            <h2 className="chat-title">{room.name}</h2>
            <p className="chat-desc">{room.description ?? ""}</p>
          </div>
          <span className={`chat-conn ${connState}`}>
            {connState === "connected" ? "실시간 연결됨"
              : connState === "connecting" ? "연결 중…" : "연결 끊김"}
          </span>
        </div>

        <div className="chat-members">
          {members.map((m) => (
            <span key={m.userId} className={`chat-member${m.online ? " online" : ""}`}>
              <span className="online-dot" aria-hidden="true" />{m.username}
            </span>
          ))}
        </div>

        <div className="chat-messages" ref={listRef}>
          {hasMore && (
            <button type="button" className="btn tiny chat-older" onClick={loadOlder}>
              이전 대화 더 보기
            </button>
          )}
          {status && <div className="status">{status}</div>}
          {messages.map((m) =>
            m.type === "TALK" ? (
              <div key={m.id} className={`chat-msg${mine(m) ? " mine" : ""}`}>
                {!mine(m) && <div className="chat-msg-sender">{m.senderNickname}</div>}
                <div className="chat-bubble">{m.content}</div>
                <span className="chat-msg-time num">{formatTime(m.createdAt)}</span>
              </div>
            ) : (
              <div key={m.id} className="chat-system">
                {m.senderNickname}님이 {m.type === "ENTER" ? "입장했습니다" : "나갔습니다"}
              </div>
            )
          )}
        </div>

        <form className="chat-input" onSubmit={handleSend}>
          <input
            placeholder="메시지를 입력하세요 (1000자 이내)"
            value={input}
            maxLength={1000}
            onChange={(e) => setInput(e.target.value)}
          />
          <button className="btn primary" disabled={connState !== "connected"}>전송</button>
        </form>
        {msg && <div className="status error">{msg}</div>}
      </div>
    </section>
  );
}

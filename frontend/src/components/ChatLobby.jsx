import { useEffect, useState } from "react";
import { createChatRoom, getChatRooms, joinChatRoom } from "../api.js";
import { chatSocket } from "../chatSocket.js";

// 채팅 로비: 방 목록(REST) + 실시간 갱신(/topic/rooms의 RoomEvent).
// 방 클릭 = join(멱등) 후 입장 — 멤버만 /topic/rooms/{id}를 구독할 수 있는 서버 계약과 짝.
export default function ChatLobby({ user, onOpenRoom }) {
  const [rooms, setRooms] = useState([]);
  const [status, setStatus] = useState("불러오는 중…");
  const [form, setForm] = useState({ name: "", description: "" });
  const [msg, setMsg] = useState("");
  const [entering, setEntering] = useState(null);   // 더블클릭 방지용 roomId

  async function load() {
    setStatus("불러오는 중…");
    try {
      const data = await getChatRooms();            // PagedModel { content, page }
      setRooms(data.content);
      setStatus(data.content.length === 0 ? "아직 채팅방이 없습니다. 첫 방을 만들어 보세요." : "");
    } catch (err) {
      setStatus(`목록을 불러오지 못했습니다: ${err.message}`);
    }
  }

  useEffect(() => {
    if (!user) return;
    load();
    // 로비 이벤트: 생성/삭제/인원 변화가 열려 있는 모든 로비 화면에 실시간 반영된다
    const unsubscribe = chatSocket.subscribeJson("/topic/rooms", (event) => {
      setRooms((prev) => {
        if (event.type === "ROOM_CREATED") {
          if (prev.some((r) => r.id === event.room.id)) return prev;
          return [event.room, ...prev];
        }
        if (event.type === "ROOM_DELETED") {
          return prev.filter((r) => r.id !== event.room.id);
        }
        // MEMBER_COUNT — memberCount/onlineCount 최신화
        return prev.map((r) => (r.id === event.room.id ? event.room : r));
      });
    });
    return unsubscribe;
  }, [user]);

  async function handleCreate(e) {
    e.preventDefault();
    setMsg("");
    try {
      const room = await createChatRoom(form.name, form.description || null);
      setForm({ name: "", description: "" });
      onOpenRoom(room);                             // 생성자는 자동 입장 상태 — 바로 진입
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function handleEnter(room) {
    if (entering) return;
    setEntering(room.id);
    setMsg("");
    try {
      await joinChatRoom(room.id);                  // 멱등 — 이미 멤버여도 성공
      onOpenRoom(room);
    } catch (err) {
      setMsg(err.message);
    } finally {
      setEntering(null);
    }
  }

  if (!user) {
    return (
      <section>
        <div className="status">채팅은 로그인 후 이용할 수 있습니다. 우측 상단에서 로그인하세요.</div>
      </section>
    );
  }

  return (
    <section>
      <div className="toolbar">
        <span className="count">
          {rooms.length > 0 && <><span className="num">{rooms.length}</span>개의 방</>}
        </span>
        <button type="button" className="btn" onClick={load}>새로고침</button>
      </div>

      {status && <div className="status" role="status">{status}</div>}
      {msg && <div className="status error">{msg}</div>}

      <ul className="board-list">
        {rooms.map((room) => (
          <li key={room.id} className="board-card clickable" onClick={() => handleEnter(room)}>
            <span className="board-id">#{room.id}</span>
            <div className="board-body">
              <p className="board-name">{room.name}</p>
              <p className="board-desc">{room.description ?? ""}</p>
              <p className="chat-room-meta">
                <span className="online-dot" aria-hidden="true" />
                <span className="num">{room.onlineCount}</span> 접속
                {" · 멤버 "}<span className="num">{room.memberCount}</span>
                {" · "}{room.ownerUsername}
              </p>
            </div>
            <span className="chevron">›</span>
          </li>
        ))}
      </ul>

      <form className="inline-form" onSubmit={handleCreate}>
        <strong>새 채팅방</strong>
        <div className="row">
          <input placeholder="방 이름 (50자 이내)" maxLength={50} required
            value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <input placeholder="설명 (선택)" maxLength={200}
            value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          <button className="btn primary">만들기</button>
        </div>
      </form>
    </section>
  );
}

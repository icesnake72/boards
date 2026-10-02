import { useEffect, useState } from "react";
import { createLiveEvent, getLiveSnapshot, getMyLiveEvents } from "../api.js";
import LiveRoom from "./LiveRoom.jsx";

// 단계 18: 라이브 입구 — 참여 코드로 들어가거나, 이벤트를 만들어 진행자가 된다.
export default function LiveHub({ user }) {
  const [code, setCode] = useState(null);
  const [mine, setMine] = useState([]);
  const [joinCode, setJoinCode] = useState("");
  const [title, setTitle] = useState("");
  const [msg, setMsg] = useState("");

  useEffect(() => {
    if (!user || code) return;
    getMyLiveEvents().then(setMine).catch((err) => setMsg(err.message));
  }, [user, code]);

  async function handleJoin(e) {
    e.preventDefault();
    setMsg("");
    const normalized = joinCode.trim().toUpperCase();
    try {
      await getLiveSnapshot(normalized);          // 없는 코드면 여기서 404 메시지
      setJoinCode("");
      setCode(normalized);
    } catch (err) {
      setMsg(err.message);
    }
  }

  async function handleCreate(e) {
    e.preventDefault();
    setMsg("");
    try {
      const created = await createLiveEvent(title);
      setTitle("");
      setCode(created.code);
    } catch (err) {
      setMsg(err.message);
    }
  }

  if (!user) {
    return (
      <section>
        <div className="status">라이브는 로그인 후 이용할 수 있습니다. 우측 상단에서 로그인하세요.</div>
      </section>
    );
  }
  if (code) {
    return <LiveRoom code={code} user={user} onBack={() => setCode(null)} />;
  }

  return (
    <section>
      {msg && <div className="status error">{msg}</div>}

      <form className="inline-form" onSubmit={handleJoin}>
        <strong>참여 코드로 입장</strong>
        <div className="row">
          <input className="live-code-input" placeholder="예: K7M2QX" maxLength={6} required
            autoCapitalize="characters" value={joinCode}
            onChange={(e) => setJoinCode(e.target.value)} />
          <button className="btn primary">입장</button>
        </div>
      </form>

      <form className="inline-form" onSubmit={handleCreate}>
        <strong>새 라이브 이벤트 (진행자)</strong>
        <div className="row">
          <input placeholder="이벤트 제목 (100자 이내)" maxLength={100} required
            value={title} onChange={(e) => setTitle(e.target.value)} />
          <button className="btn">만들기</button>
        </div>
      </form>

      <h2 className="section-title">내가 진행하는 이벤트</h2>
      {mine.length === 0 && <div className="status">아직 만든 이벤트가 없습니다.</div>}
      <ul className="board-list">
        {mine.map((ev) => (
          <li key={ev.id} className="board-card clickable" onClick={() => setCode(ev.code)}>
            <span className="board-id live-code">{ev.code}</span>
            <div className="board-body">
              <p className="board-name">{ev.title}</p>
              <p className="board-desc">{ev.status === "OPEN" ? "진행 중" : "종료됨"}</p>
            </div>
            <span className="chevron">›</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

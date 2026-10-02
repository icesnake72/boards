import { useCallback, useEffect, useState } from "react";
import {
  closeLiveEvent, closePoll, createPoll, createQuestion, deleteQuestion,
  getLiveSnapshot, toggleQuestionLike, votePoll,
} from "../api.js";
import { openLiveStream } from "../liveStream.js";

// 서버 QuestionResponse.RANKING과 같은 규칙: 좋아요 많은 순, 같으면 최신(id 큰) 순.
function rank(questions) {
  return [...questions].sort((a, b) => b.likeCount - a.likeCount || b.id - a.id);
}

// 득표수는 늘기만 한다 → SSE가 순서가 뒤바뀌어 도착해도 옵션별 큰 값을 취하면 최신 값이 남는다.
// myOptionId는 내 REST 응답에만 있으므로(SSE는 null) 기존 값을 지킨다.
function mergePoll(prev, next) {
  const before = new Map(prev.options.map((o) => [o.id, o.count]));
  const options = next.options.map((o) => ({ ...o, count: Math.max(o.count, before.get(o.id) ?? 0) }));
  return {
    ...prev,
    ...next,
    options,
    totalVotes: options.reduce((sum, o) => sum + o.count, 0),
    myOptionId: next.myOptionId ?? prev.myOptionId,
    status: prev.status === "CLOSED" ? "CLOSED" : next.status,
  };
}

const CONN_LABEL = {
  connecting: "연결 중…",
  live: "실시간 연결됨",
  reconnecting: "재연결 중…",
  closed: "연결 종료",
};

export default function LiveRoom({ code, onBack }) {
  const [snap, setSnap] = useState(null);
  const [conn, setConn] = useState("connecting");
  const [msg, setMsg] = useState("");
  const [question, setQuestion] = useState("");
  const [pollForm, setPollForm] = useState({ title: "", options: ["", ""] });

  const load = useCallback(async () => {
    try {
      const data = await getLiveSnapshot(code);
      setSnap({ ...data, questions: rank(data.questions) });
    } catch (err) {
      setMsg(err.message);
    }
  }, [code]);

  const applyEvent = useCallback((name, data) => {
    setSnap((prev) => {
      if (!prev) return prev;        // 스냅샷 도착 전 이벤트는 곧 올 스냅샷에 이미 반영돼 있다
      switch (name) {
        case "question.created":
          if (prev.questions.some((q) => q.id === data.id)) return prev;
          return { ...prev, questions: rank([...prev.questions, data]) };
        case "question.liked":
          return {
            ...prev,
            questions: rank(prev.questions.map((q) =>
              (q.id === data.id ? { ...q, likeCount: data.likeCount } : q))),
          };
        case "question.deleted":
          return { ...prev, questions: prev.questions.filter((q) => q.id !== data.id) };
        case "poll.created":
          if (prev.polls.some((p) => p.id === data.id)) return prev;
          return { ...prev, polls: [data, ...prev.polls] };
        case "poll.voted":
          return { ...prev, polls: prev.polls.map((p) => (p.id === data.id ? mergePoll(p, data) : p)) };
        case "poll.closed":
          return { ...prev, polls: prev.polls.map((p) => (p.id === data.id ? { ...p, status: "CLOSED" } : p)) };
        case "event.closed":
          return { ...prev, event: { ...prev.event, status: "CLOSED" } };
        default:
          return prev;
      }
    });
  }, []);

  // 순서가 핵심: 스트림을 먼저 열고 connected를 받은 "뒤에" 스냅샷을 읽는다.
  // 스냅샷을 먼저 읽으면 [스냅샷 응답 ~ 구독 시작] 사이의 변화를 영영 놓친다.
  useEffect(
    () => openLiveStream(code, { onConnected: load, onEvent: applyEvent, onStatus: setConn }),
    [code, load, applyEvent],
  );

  async function run(action) {
    setMsg("");
    try {
      await action();
    } catch (err) {
      setMsg(err.message);
    }
  }

  const submitQuestion = (e) => {
    e.preventDefault();
    run(async () => {
      const created = await createQuestion(code, question);
      setQuestion("");
      // SSE(mine=false)가 먼저 왔을 수 있다 → 내 응답(mine=true)으로 교체
      setSnap((prev) => ({
        ...prev,
        questions: rank([...prev.questions.filter((q) => q.id !== created.id), created]),
      }));
    });
  };

  const like = (id) => run(async () => {
    const res = await toggleQuestionLike(id);
    setSnap((prev) => ({
      ...prev,
      questions: rank(prev.questions.map((q) =>
        (q.id === id ? { ...q, likedByMe: res.liked, likeCount: res.likeCount } : q))),
    }));
  });

  const remove = (id) => run(async () => {
    await deleteQuestion(id);
    setSnap((prev) => ({ ...prev, questions: prev.questions.filter((q) => q.id !== id) }));
  });

  const vote = (pollId, optionId) => run(async () => {
    const res = await votePoll(pollId, optionId);
    setSnap((prev) => ({ ...prev, polls: prev.polls.map((p) => (p.id === pollId ? mergePoll(p, res) : p)) }));
  });

  const submitPoll = (e) => {
    e.preventDefault();
    run(async () => {
      const options = pollForm.options.map((o) => o.trim()).filter(Boolean);
      const created = await createPoll(code, pollForm.title, options);
      setPollForm({ title: "", options: ["", ""] });
      setSnap((prev) => (prev.polls.some((p) => p.id === created.id)
        ? prev
        : { ...prev, polls: [created, ...prev.polls] }));
    });
  };

  const endPoll = (id) => run(async () => {
    await closePoll(id);
    setSnap((prev) => ({ ...prev, polls: prev.polls.map((p) => (p.id === id ? { ...p, status: "CLOSED" } : p)) }));
  });

  const endEvent = () => run(async () => {
    await closeLiveEvent(code);
    setSnap((prev) => ({ ...prev, event: { ...prev.event, status: "CLOSED" } }));
  });

  if (!snap) {
    return (
      <section>
        <button type="button" className="btn tiny" onClick={onBack}>‹ 라이브 목록</button>
        <div className={`status${msg ? " error" : ""}`}>{msg || "불러오는 중…"}</div>
      </section>
    );
  }

  const { event, questions, polls } = snap;
  const open = event.status === "OPEN";
  const isHost = event.host;

  return (
    <section className="live-room">
      <div className="toolbar">
        <button type="button" className="btn tiny" onClick={onBack}>‹ 라이브 목록</button>
        <span className={`live-conn ${conn}`} role="status">{CONN_LABEL[conn]}</span>
      </div>

      <header className="live-head">
        <p className="live-code" aria-label="참여 코드">{event.code}</p>
        <h2 className="live-title">{event.title}</h2>
        <p className="live-meta">진행 {event.hostUsername} · {open ? "진행 중" : "종료됨"}</p>
        {isHost && open && (
          <button type="button" className="btn tiny" onClick={endEvent}>이벤트 종료</button>
        )}
      </header>

      {msg && <div className="status error">{msg}</div>}

      <div className="live-grid">
        <div>
          <h3 className="section-title">투표</h3>
          {isHost && open && (
            <form className="inline-form" onSubmit={submitPoll}>
              <strong>새 투표</strong>
              <input placeholder="질문 (100자 이내)" maxLength={100} required value={pollForm.title}
                onChange={(e) => setPollForm({ ...pollForm, title: e.target.value })} />
              {pollForm.options.map((opt, i) => (
                <input key={i} placeholder={`선택지 ${i + 1}`} maxLength={50} required value={opt}
                  onChange={(e) => setPollForm({
                    ...pollForm,
                    options: pollForm.options.map((o, j) => (j === i ? e.target.value : o)),
                  })} />
              ))}
              <div className="row">
                {pollForm.options.length < 5 && (
                  <button type="button" className="btn tiny"
                    onClick={() => setPollForm({ ...pollForm, options: [...pollForm.options, ""] })}>
                    선택지 추가
                  </button>
                )}
                <button className="btn primary">투표 시작</button>
              </div>
            </form>
          )}
          {polls.length === 0 && <div className="status">아직 투표가 없습니다.</div>}
          {polls.map((poll) => (
            <PollCard key={poll.id} poll={poll}
              canVote={open && poll.status === "OPEN" && poll.myOptionId == null}
              canClose={isHost && open && poll.status === "OPEN"}
              onVote={vote} onClose={endPoll} />
          ))}
        </div>

        <div>
          <h3 className="section-title">질문 <span className="num">{questions.length}</span></h3>
          {open && (
            <form className="inline-form" onSubmit={submitQuestion}>
              <div className="row">
                <input placeholder="질문을 입력하세요 (300자 이내)" maxLength={300} required
                  value={question} onChange={(e) => setQuestion(e.target.value)} />
                <button className="btn primary">등록</button>
              </div>
            </form>
          )}
          <ul className="question-list">
            {questions.map((q) => (
              <li key={q.id} className="question-item">
                <button type="button" className={`like-btn${q.likedByMe ? " liked" : ""}`}
                  aria-pressed={q.likedByMe} disabled={!open} onClick={() => like(q.id)}>
                  ▲ <span className="num">{q.likeCount}</span>
                </button>
                <div className="question-body">
                  <p className="question-content">{q.content}</p>
                  <p className="question-meta">{q.authorUsername}</p>
                </div>
                {(q.mine || isHost) && (
                  <button type="button" className="btn tiny" onClick={() => remove(q.id)}>삭제</button>
                )}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

function PollCard({ poll, canVote, canClose, onVote, onClose }) {
  return (
    <article className="poll-card">
      <div className="poll-head">
        <strong>{poll.title}</strong>
        <span className="poll-status">
          {poll.status === "OPEN" ? "진행 중" : "마감"} · <span className="num">{poll.totalVotes}</span>표
        </span>
      </div>
      <ul className="poll-options">
        {poll.options.map((o) => {
          const pct = poll.totalVotes === 0 ? 0 : Math.round((o.count / poll.totalVotes) * 100);
          return (
            <li key={o.id}>
              <button type="button" className={`poll-option${poll.myOptionId === o.id ? " mine" : ""}`}
                disabled={!canVote} onClick={() => onVote(poll.id, o.id)}>
                <span className="poll-bar" style={{ width: `${pct}%` }} aria-hidden="true" />
                <span className="poll-text">{o.text}</span>
                <span className="poll-count num">{o.count} · {pct}%</span>
              </button>
            </li>
          );
        })}
      </ul>
      {canClose && <button type="button" className="btn tiny" onClick={() => onClose(poll.id)}>투표 마감</button>}
    </article>
  );
}

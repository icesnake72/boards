// 단계 18: 라이브 화면 상태 규칙(순수 함수) — React 없이 테스트할 수 있게 LiveRoom에서 분리했다.

// 참여 코드는 6자 — 붙여넣은 값의 공백을 먼저 지운 뒤 자른다.
// (input의 maxLength로 자르면 " K7M2QX"가 " K7M2Q"로 잘려 공백 제거 전에 글자를 잃는다)
export function sanitizeJoinCode(value) {
  return value.replace(/\s/g, "").toUpperCase().slice(0, 6);
}

// 서버 QuestionResponse.RANKING과 같은 규칙: 좋아요 많은 순, 같으면 최신(id 큰) 순.
export function rank(questions) {
  return [...questions].sort((a, b) => b.likeCount - a.likeCount || b.id - a.id);
}

// 득표수는 늘기만 한다 → SSE가 순서가 뒤바뀌어 도착해도 옵션별 큰 값을 취하면 최신 값이 남는다.
// myOptionId는 내 REST 응답에만 있으므로(SSE는 null) 기존 값을 지킨다.
export function mergePoll(prev, next) {
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

// SSE 이벤트 하나를 화면 상태에 반영한 새 상태를 돌려준다(원본 불변).
export function applyLiveEvent(state, name, data) {
  switch (name) {
    case "question.created":
      if (state.questions.some((q) => q.id === data.id)) return state;
      return { ...state, questions: rank([...state.questions, data]) };
    case "question.liked":
      return {
        ...state,
        questions: rank(state.questions.map((q) =>
          (q.id === data.id ? { ...q, likeCount: data.likeCount } : q))),
      };
    case "question.deleted":
      return { ...state, questions: state.questions.filter((q) => q.id !== data.id) };
    case "poll.created":
      if (state.polls.some((p) => p.id === data.id)) return state;
      return { ...state, polls: [data, ...state.polls] };
    case "poll.voted":
      return { ...state, polls: state.polls.map((p) => (p.id === data.id ? mergePoll(p, data) : p)) };
    case "poll.closed":
      return { ...state, polls: state.polls.map((p) => (p.id === data.id ? { ...p, status: "CLOSED" } : p)) };
    case "event.closed":
      return { ...state, event: { ...state.event, status: "CLOSED" } };
    default:
      return state;
  }
}

// 스냅샷을 받는 동안 도착한 SSE 이벤트를 붙잡아 두는 관문.
// 서버가 스냅샷을 읽은 "뒤에" 커밋된 변화는 스냅샷에 없지만, 그 SSE는 스냅샷 응답보다 먼저 올 수 있다.
// 그 이벤트를 옛 상태에 적용하면 곧 도착할 스냅샷이 덮어써 사라진다 → 보관했다가 스냅샷 위에 다시 적용한다.
// 위 규칙들은 중복 추가 방지·max 병합이라 같은 이벤트를 두 번 적용해도 안전하다.
export function createSnapshotGate() {
  let pending = null;          // null = 실시간 모드, 배열 = 스냅샷 대기 중
  let latest = 0;              // 재연결이 겹쳐 요청이 여러 개면 마지막 것만 유효

  return {
    begin() {
      pending = [];
      latest += 1;
      return latest;
    },
    // 스냅샷 대기 중이면 보관하고 true — 호출자는 이번 이벤트를 직접 적용하지 않는다.
    offer(name, data) {
      if (pending === null) return false;
      pending.push([name, data]);
      return true;
    },
    // 옛 요청의 응답이면 null(버린다). 최신이면 보관 이벤트를 다시 적용한 상태를 돌려준다.
    finish(token, snapshot) {
      if (token !== latest) return null;
      const replay = pending ?? [];
      pending = null;
      return replay.reduce(
        (state, [name, data]) => applyLiveEvent(state, name, data),
        { ...snapshot, questions: rank(snapshot.questions) },
      );
    },
    fail(token) {
      if (token === latest) pending = null;
    },
  };
}

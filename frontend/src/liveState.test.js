// 단계 18: 라이브 화면 상태 규칙 — 의존성 없이 Node 내장 test runner로 실행한다(npm test).
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  applyLiveEvent, createSnapshotGate, mergePoll, rank, sanitizeJoinCode,
} from "./liveState.js";

const question = (id, likeCount = 0) => ({ id, content: `q${id}`, likeCount, likedByMe: false, mine: false });
const poll = (id, counts, myOptionId = null) => ({
  id,
  title: "점심",
  status: "OPEN",
  options: counts.map((count, i) => ({ id: i + 1, text: `o${i + 1}`, count })),
  totalVotes: counts.reduce((a, b) => a + b, 0),
  myOptionId,
});
const snapshot = (questions = [], polls = []) => ({
  event: { id: 1, code: "K7M2QX", status: "OPEN", host: false },
  questions,
  polls,
});

test("붙여넣은 코드의 공백을 지우고 대문자 6자로 맞춘다", () => {
  assert.equal(sanitizeJoinCode(" k7m2qx "), "K7M2QX");
  assert.equal(sanitizeJoinCode(" K7M2QX"), "K7M2QX");
  assert.equal(sanitizeJoinCode("K7 M2 QX 9"), "K7M2QX");
});

test("좋아요 많은 순, 같으면 최신(id 큰) 순", () => {
  assert.deepEqual(rank([question(1, 0), question(2, 3), question(3, 0)]).map((q) => q.id), [2, 3, 1]);
});

test("득표수는 옵션별 큰 값을 취하고 내 선택은 지킨다", () => {
  const merged = mergePoll(poll(7, [2, 1], 1), poll(7, [1, 3], null));
  assert.deepEqual(merged.options.map((o) => o.count), [2, 3]);
  assert.equal(merged.totalVotes, 5);
  assert.equal(merged.myOptionId, 1);
});

test("이미 있는 질문의 created 이벤트는 중복 추가하지 않는다", () => {
  const state = snapshot([question(1)]);
  assert.equal(applyLiveEvent(state, "question.created", question(1)).questions.length, 1);
});

test("스냅샷을 받는 동안 도착한 이벤트는 스냅샷 위에 다시 적용된다", () => {
  const gate = createSnapshotGate();
  const token = gate.begin();
  assert.equal(gate.offer("question.created", question(9)), true);   // 버퍼에 보관

  const state = gate.finish(token, snapshot([question(1)]));

  assert.deepEqual(state.questions.map((q) => q.id), [9, 1]);
});

test("스냅샷을 받는 중이 아니면 이벤트를 보관하지 않는다", () => {
  const gate = createSnapshotGate();
  assert.equal(gate.offer("question.created", question(9)), false);
});

test("늦게 도착한 옛 스냅샷 응답은 버린다", () => {
  const gate = createSnapshotGate();
  const first = gate.begin();
  const second = gate.begin();

  assert.equal(gate.finish(first, snapshot([question(1)])), null);
  assert.deepEqual(gate.finish(second, snapshot([question(2)])).questions.map((q) => q.id), [2]);
});

test("스냅샷 요청이 실패하면 보관 중이던 이벤트를 비우고 다시 실시간 모드로 돌아간다", () => {
  const gate = createSnapshotGate();
  const token = gate.begin();
  gate.offer("question.created", question(9));

  gate.fail(token);

  assert.equal(gate.offer("question.created", question(10)), false);
});

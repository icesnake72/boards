import { useEffect, useState } from "react";
import { chatSocket } from "../chatSocket.js";
import ChatLobby from "./ChatLobby.jsx";
import ChatRoom from "./ChatRoom.jsx";

// 채팅 영역의 뿌리: STOMP 연결 수명주기를 소유한다.
// 채팅 탭에 머무는 동안 연결 하나를 유지하고(로비·방은 그 위의 구독일 뿐),
// 탭을 떠나거나 로그아웃하면 연결을 정리한다 — 서버의 DISCONNECT 처리(LEAVE)와 짝.
export default function ChatHub({ user }) {
  const [view, setView] = useState({ name: "lobby" });
  const [connState, setConnState] = useState("offline");

  useEffect(() => {
    if (!user) return;
    chatSocket.setStateListener(setConnState);
    chatSocket.activate();
    return () => {
      chatSocket.setStateListener(null);
      chatSocket.deactivate();
    };
  }, [user]);

  if (view.name === "room") {
    return (
      <ChatRoom
        room={view.room}
        user={user}
        connState={connState}
        onBack={() => setView({ name: "lobby" })}
      />
    );
  }
  return <ChatLobby user={user} onOpenRoom={(room) => setView({ name: "room", room })} />;
}

import { useEffect, useState } from "react";
import {
  createComment, deleteComment, deletePost,
  getComments, getPost, reactToComment, reactToPost,
} from "../api.js";

function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// 반응 버튼 한 쌍 — myReaction이면 강조. onReact(type)는 토글/전환을 서버에 위임.
function Reactions({ likeCount, dislikeCount, myReaction, onReact, disabled }) {
  return (
    <span className="reactions">
      <button type="button" disabled={disabled} onClick={() => onReact("LIKE")}
        className={`btn tiny${myReaction === "LIKE" ? " active" : ""}`}>👍 {likeCount}</button>
      <button type="button" disabled={disabled} onClick={() => onReact("DISLIKE")}
        className={`btn tiny${myReaction === "DISLIKE" ? " active" : ""}`}>👎 {dislikeCount}</button>
    </span>
  );
}

function Comment({ c, user, onReply, onDelete, onReact, depth = 0 }) {
  const [replyOpen, setReplyOpen] = useState(false);
  const [reply, setReply] = useState("");
  const mine = user && c.authorUsername === user.username;

  return (
    <li className={depth === 0 ? "comment" : "comment reply"}>
      <div className="comment-head">
        <strong>{c.authorUsername}</strong>
        <span className="post-meta">{formatDate(c.createdAt)}</span>
      </div>
      <p className="comment-content">{c.deleted ? "삭제된 댓글입니다" : c.content}</p>
      {!c.deleted && (
        <div className="comment-actions">
          <Reactions likeCount={c.likeCount} dislikeCount={c.dislikeCount}
            myReaction={c.myReaction} disabled={!user} onReact={(t) => onReact(c.id, t)} />
          {user && depth === 0 && (
            <button type="button" className="btn tiny" onClick={() => setReplyOpen(!replyOpen)}>답글</button>
          )}
          {mine && (
            <button type="button" className="btn tiny danger" onClick={() => onDelete(c.id)}>삭제</button>
          )}
        </div>
      )}
      {replyOpen && (
        <form className="row" onSubmit={(e) => { e.preventDefault(); onReply(c.id, reply); setReply(""); setReplyOpen(false); }}>
          <input placeholder="대댓글 내용" value={reply} onChange={(e) => setReply(e.target.value)} />
          <button className="btn tiny primary">등록</button>
        </form>
      )}
      {c.children?.length > 0 && (
        <ul className="comment-children">
          {c.children.map((ch) => (
            <Comment key={ch.id} c={ch} user={user} depth={depth + 1}
              onReply={onReply} onDelete={onDelete} onReact={onReact} />
          ))}
        </ul>
      )}
    </li>
  );
}

// 글 상세: 본문·이미지·조회수 + 글 반응 + 댓글 트리(작성/대댓글/삭제/반응).
export default function PostDetail({ postId, user, onBack }) {
  const [post, setPost] = useState(null);
  const [comments, setComments] = useState([]);
  const [status, setStatus] = useState("불러오는 중…");
  const [comment, setComment] = useState("");
  const [msg, setMsg] = useState("");

  async function load() {
    setStatus("불러오는 중…");
    try {
      const [p, c] = await Promise.all([getPost(postId), getComments(postId)]);
      setPost(p);
      setComments(c.content);
      setStatus("");
    } catch (err) {
      setStatus(`불러오지 못했습니다: ${err.message}`);
    }
  }

  useEffect(() => { load(); }, [postId]);

  async function withMsg(fn) {
    setMsg("");
    try { await fn(); } catch (err) { setMsg(err.message); }
  }

  const handlePostReact = (type) => withMsg(async () => {
    const r = await reactToPost(postId, type);   // {likeCount, dislikeCount, myReaction}
    setPost({ ...post, ...r });
  });
  const handleComment = (e) => { e.preventDefault(); withMsg(async () => {
    await createComment(postId, comment); setComment(""); load();
  }); };
  const handleReply = (parentId, content) => withMsg(async () => {
    await createComment(postId, content, parentId); load();
  });
  const handleCommentReact = (id, type) => withMsg(async () => {
    await reactToComment(id, type); load();
  });
  const handleCommentDelete = (id) => withMsg(async () => {
    await deleteComment(id); load();
  });
  const handlePostDelete = () => withMsg(async () => {
    await deletePost(postId); onBack();
  });

  if (!post) return <section><div className="status">{status}</div></section>;
  const mine = user && post.authorUsername === user.username;

  return (
    <section>
      <div className="toolbar">
        <button type="button" className="btn" onClick={onBack}>← 글 목록</button>
        {mine && <button type="button" className="btn danger" onClick={handlePostDelete}>글 삭제</button>}
      </div>

      <article className="post-detail">
        <h2>{post.title}</h2>
        <p className="post-meta">
          {post.authorUsername} · 조회 {post.viewCount} · {formatDate(post.createdAt)} · {post.boardName}
        </p>
        <p className="post-content">{post.content}</p>
        {post.images?.length > 0 && (
          <div className="post-images">
            {post.images.map((img) => <img key={img.id ?? img.url} src={img.url} alt="" />)}
          </div>
        )}
        <Reactions likeCount={post.likeCount} dislikeCount={post.dislikeCount}
          myReaction={post.myReaction} disabled={!user} onReact={handlePostReact} />
      </article>

      <h3 className="section-title">댓글 {comments.length > 0 ? `(${comments.length})` : ""}</h3>
      {status && <div className="status">{status}</div>}
      <ul className="comment-list">
        {comments.map((c) => (
          <Comment key={c.id} c={c} user={user}
            onReply={handleReply} onDelete={handleCommentDelete} onReact={handleCommentReact} />
        ))}
      </ul>

      {user ? (
        <form className="row" onSubmit={handleComment}>
          <input placeholder="댓글을 입력하세요" value={comment} onChange={(e) => setComment(e.target.value)} />
          <button className="btn primary">등록</button>
        </form>
      ) : (
        <div className="status">댓글을 쓰려면 로그인하세요.</div>
      )}
      {msg && <div className="status error">{msg}</div>}
    </section>
  );
}

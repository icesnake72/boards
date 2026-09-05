---
track: reference
tags: [frontend, react, deployment, performance]
requires: ["[[DB-PERFORMANCE-WALKTHROUGH]]", "[[FRONTEND-DEPLOY]]"]
status: 완료
---

# 프론트 재편 + 하이브리드 페이지네이션 — 작업 순서별 기록

> 두 가지 작업을 한 흐름으로 기록한다: ① **프론트 재편** — React가 `frontend/`
> 이름을 승계하고 순수 JS 원형은 `frontend-vanilla/` 학습 자료로 은퇴,
> ② **게시글 목록 페이지네이션 UI** — 페이지 점프(offset) + 무한스크롤(keyset)
> 하이브리드 + 최상단 버튼. 단계 16([[DB-PERFORMANCE-WALKTHROUGH]])의 두 API가
> 화면에서 각자의 자리를 찾는 이야기이기도 하다.

---

## 0. 작업 지도 — 무엇을 어떤 순서로

| 순서 | 작업 | 이 순서인 이유 |
|------|------|----------------|
| 1 | 디렉토리 개명 (`git mv`) | 이후 모든 수정의 전제 — 이력 보존을 위해 mv가 먼저 |
| 2 | docker-compose 재편 | 서비스·이미지·컨테이너 이름 승계 결정이 3·4의 기준 |
| 3 | Caddyfile 업스트림 교체 | compose의 컨테이너명 결정을 따라감 |
| 4 | deploy.yml·deploy.sh | 빌드 대상 축소(3→2종) + `--remove-orphans` |
| 5 | 페이지네이션 UI (api.js·Posts.jsx·styles.css) | 인프라가 안정된 뒤 기능 작업 |
| 6 | 로컬 검증 (npm build → 브라우저 E2E) | 배포 전 관문 |
| 7 | main 머지 → 배포 → 운영 검증 | 마지막 — 사고 시 롤백 범위를 좁게 |

실제로 7번에서 사고가 났고(§4), 그 처치까지가 이 기록의 일부다.

---

## 1. 프론트 재편 — 이름 매핑

| | 재편 전 | 재편 후 |
|---|---------|---------|
| React 디렉토리 | `frontend-react/` | **`frontend/`** (승계) |
| 순수 JS 디렉토리 | `frontend/` | `frontend-vanilla/` (학습 보존·배포 제외) |
| compose 서비스/컨테이너 | `frontend-react` / `board-frontend-react` | `frontend` / `board-frontend` |
| 이미지 | `board-frontend-react` + `board-frontend`(vanilla) | `board-frontend` 하나 |
| 공개 포트 | (vanilla가 8070 publish) | 없음 — caddy(80/443)만 공개 |

파급 지점과 처치:

- **docker-compose.yml**: vanilla 서비스 삭제(8070 소멸), React 서비스가 `frontend`
  이름 승계. caddy `depends_on`도 교체
- **caddy/Caddyfile**: `reverse_proxy board-frontend:80` (2곳)
- **deploy.yml**: 이미지 빌드 3종 → 2종(app·frontend)
- **deploy.sh**: `docker compose up`에 **`--remove-orphans`** 추가 — compose에서
  사라진 서비스(`frontend-react`)의 잔존 컨테이너를 배포가 정리한다
- **.gitignore**: `node_modules/`·`frontend/dist/` (이참에 누락 발견·추가)

> 주의: 서비스 이름을 "삭제+신규"가 아니라 **승계**로 설계한 이유 — 서버의
> 기존 `board-frontend` 컨테이너 자리를 새 이미지가 그대로 대체(recreate)하므로
> 이름 충돌이 없다. 반대로 `frontend-react`는 orphan이 되어 `--remove-orphans`가
> 지운다.

---

## 2. 하이브리드 페이지네이션 — 설계

요구: ① 특정 페이지로 바로 점프, ② 스크롤로 이어 보기(무한스크롤), ③ 스크롤
위치에 따라 현재 페이지 번호가 따라오는 UI, ④ 최상단 이동 버튼.

단계 16에서 만든 두 API가 각자 잘하는 일을 맡는다:

| 동작 | API | 근거 |
|------|-----|------|
| 페이지 번호 점프 | offset (`?page=N`) | "N번째 페이지"는 위치 기반 질의 — offset의 본업. 단계 16에서 "점프용으로 유지"한 이유가 이것 |
| 스크롤 이어 보기 | keyset (`/posts/cursor`) | 깊어져도 느려지지 않음 ([[DB-PERFORMANCE-LAB]] §6) |

두 방식의 접점: **점프한 offset 페이지의 마지막 행이 그대로 keyset 커서**가 된다
(두 API의 정렬 기준이 동일하므로). 목록은 "페이지 블록" 단위로 렌더하고, 점프는
블록을 교체, 스크롤은 블록을 이어 붙인다.

```mermaid
flowchart LR
  J["번호 클릭"] -->|"offset ?page=N"| B["블록 교체 + totalPages 갱신"]
  B -->|"마지막 행 → 커서"| S["스크롤"]
  S -->|"keyset cursor"| A["블록 N+1 이어 붙임"]
  A --> S
```

---

## 3. 구현 핵심 (`frontend/src/components/Posts.jsx`)

전체 코드는 소스 참조(커밋 `b4b7c54`, `e3eb2f9`). 여기서는 함정을 막는 장치
세 가지만 해설한다.

**① 세대(generation) 가드 — 점프 vs 진행 중 로드의 경쟁**

바닥에서 keyset 로드가 진행 중일 때 점프를 누르면, 점프 결과 위에 옛 스크롤
응답이 덧붙는 경쟁이 생긴다(실제로 첫 구현에서 점프 클릭이 무시되는 버그로
발현). 점프마다 세대 번호를 올리고, 이전 세대의 응답은 도착해도 버린다:

```jsx
const genRef = useRef(0);
// jumpToPage: const gen = ++genRef.current; ... 응답 후 genRef.current !== gen이면 폐기
// loadMore:   const gen = genRef.current;   ... 응답 후 세대가 바뀌었으면 폐기
```

**② 커서·로딩 플래그는 state가 아닌 ref**

IntersectionObserver 콜백은 등록 시점의 클로저를 계속 쓰므로, state에 둔 커서는
낡은 값을 보낼 수 있다(React의 stale closure 함정). 렌더에 쓰지 않는 값은
`cursorRef`·`loadingRef`로 보관한다.

**③ 현재 페이지 추적 — offsetTop 비교 (결정적)**

블록마다 IntersectionObserver를 다는 방식은 로드·리렌더 타이밍에 따라 번호가
흔들렸다. "뷰포트 상단 30% 지점(앵커)을 지나 있는 마지막 블록"을 offsetTop으로
계산하는 스크롤 리스너가 결정적이다:

```jsx
const anchor = window.scrollY + window.innerHeight * 0.3;
let cur = pageBlocks.length ? pageBlocks[0].no : 1;
for (const block of pageBlocks) {
  const el = blockRefs.current.get(block.no);
  if (el && el.offsetTop <= anchor) cur = block.no;
}
setCurrentPage(cur);
```

그 외: 페이지 바는 `1 … c-2~c+2 … 끝` 윈도우(하단 sticky), 이미 로드된 블록
번호를 누르면 점프 대신 `scrollIntoView`, 최상단 버튼은 `scrollY > 400`에서만
표시. deep 점프가 처음 3.2초였던 문제는 백엔드 지연 조인으로 60ms가 됐다 —
[[DB-PERFORMANCE-WALKTHROUGH]] 작업 9.

---

## 4. 배포와 사고 — caddy가 옛 설정을 붙들고 있었다

배포(test·build·deploy 성공) 직후 **사이트 전체 502**. caddy 로그가 범인을
지목했다: `lookup board-frontend-react ... no such host` — 방금 orphan으로 제거된
옛 업스트림을 여전히 찾고 있었다.

원인은 **단일 파일 bind mount의 inode 함정**이다. `git reset --hard`가 Caddyfile을
새 inode로 교체해도, 파일 하나를 마운트한 컨테이너는 옛 inode를 계속 본다 —
`caddy reload`조차 옛 내용을 다시 읽는다. 상세 전말과 재발 방지(디렉토리 마운트
전환 + deploy.sh의 무중단 reload 단계)는 [[HTTPS-DOMAIN]] §10-1에 기록했다.

> 중요: compose는 ① bind mount의 **내용** 변경, ② `depends_on` 변경으로는
> 컨테이너를 재생성하지 않는다. "compose up을 했으니 반영됐겠지"가 통하지 않는
> 두 사례 — 설정 파일 변경은 명시적 reload/재기동이 필요하다.

---

## 5. 검증 기록

| 검증 | 환경 | 결과 |
|------|------|------|
| npm build + compose config | 로컬 | 통과 |
| 무한스크롤 | 로컬 100만 건, 브라우저 | 20→40→60건, 전부 유니크 |
| 현재 페이지 추적 | 〃 | 스크롤에 따라 1→2 갱신 |
| 페이지 점프 | 〃 | 9999·10001(마지막, 7건+센티널 소멸) |
| 최상단 버튼 | 〃 | 400px 스크롤 시 표시, 클릭 시 scrollY 0 |
| 운영 스모크 | sbs.alldayai.org | 프론트/API/cursor 200, deep page 0.19s |
| 운영 브라우저 E2E | 〃 (20만 건) | 바 `1 2 3 … 10000`, 스크롤·점프·top 버튼 정상 |

---

## 부록: 변경 파일 요약 (커밋 `b4b7c54` + `0c36871`)

| 파일 | 변경 |
|------|------|
| `frontend-react/` → `frontend/`, `frontend/` → `frontend-vanilla/` | git mv (이력 보존) |
| `docker-compose.yml` | vanilla 서비스 삭제, React가 frontend 승계, caddy 디렉토리 마운트 |
| `caddy/Caddyfile` | 업스트림 `board-frontend` |
| `.github/workflows/deploy.yml` | 이미지 3종 → 2종 |
| `scripts/deploy.sh` | `--remove-orphans` + caddy 무중단 reload |
| `frontend/src/api.js` | (경로만 변경) |
| `frontend/src/components/Posts.jsx` | 하이브리드 페이지네이션 + top 버튼 |
| `frontend/src/styles.css` | `.pagination`·`.page-btn`·`.top-btn` |
| `.gitignore` | `node_modules/`·`frontend/dist/` 추가 |

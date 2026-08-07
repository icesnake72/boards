---
type: 참조
track: reference
tags: [reference, deployment, aws, lightsail, docker]
requires: ["[[DOCKER]]", "[[FRONTEND-DEPLOY]]", "[[CICD-GITHUB-ACTIONS]]"]
status: 완료
---

# AWS Lightsail 배포 설치 가이드 (서버 최초 세팅)

- **대상 서버**: AWS Lightsail 인스턴스 `3.34.173.34` (Ubuntu 기준)
- **목표**: 로컬에서 하던 방식 그대로(`docker compose`) Lightsail에서 백엔드 + 프론트 2종을 띄우고, 이후 배포는 [[CICD-GITHUB-ACTIONS]]의 파이프라인이 자동으로 잇는다.
- **역할 분담**: 이 문서는 **서버를 한 번 준비하는 수동 작업**(Docker·MySQL·네트워크·저장소)이다. 코드가 바뀔 때마다의 재배포는 GitHub Actions가 SSH로 처리하므로 다시 손댈 필요가 없다.

---

## 1. 배포 구조 한눈에

```mermaid
flowchart LR
  DEV["개발자 git push (main)"] --> GH["GitHub Actions"]
  GH -->|"① test 통과"| GH
  GH -->|"② SSH 접속"| LS["Lightsail 3.34.173.34"]
  subgraph LS["Lightsail 인스턴스"]
    APP["board-app :8090"] -->|"board-db-net"| DB[("mysql-8 : board")]
    FE1["board-frontend :8070"] -->|"/api 프록시"| APP
    FE2["board-frontend-react :8071"] -->|"/api 프록시"| APP
  end
  USER["사용자 브라우저"] -->|":8070 / :8071"| LS
```

서버가 갖춰야 할 것: **Docker + docker compose**, **mysql-8 컨테이너**, **board-db-net 네트워크**, **저장소 클론(~/board)**, **방화벽 포트 개방**. 아래 순서대로 준비한다.

---

## 2. Lightsail 방화벽(네트워킹) 포트 개방

Lightsail 콘솔 → 인스턴스 → **네트워킹** 탭 → **IPv4 방화벽** 에서 규칙 추가:

| 애플리케이션 | 프로토콜 | 포트 | 용도 |
|---|---|---|---|
| SSH | TCP | 22 | 접속·배포(기본 열림) |
| Custom | TCP | 8070 | 순수 JS 프론트 |
| Custom | TCP | 8071 | React 프론트 |
| Custom | TCP | 8090 | 백엔드 API(직접 호출·테스트용, 선택) |

> [!NOTE]
> 프론트(8070/8071)는 내부에서 `/api`를 백엔드로 프록시하므로, 최소 요건은 8070·8071·22 다. 8090은 curl 테스트를 위해 열어두면 편하다. 실서비스라면 80/443으로 좁히고 나머지는 닫는다.

---

## 3. SSH 접속

방금 발급한 키(`webserver_key.pem`, git에는 커밋하지 않음)로 접속한다.

```bash
chmod 400 webserver_key.pem
ssh -i webserver_key.pem ubuntu@3.34.173.34
```

- 사용자명은 블루프린트에 따라 다르다: **Ubuntu → `ubuntu`**, Amazon Linux → `ec2-user`, Bitnami → `bitnami`.

---

## 4. Docker 설치 (서버에서)

```bash
# 공식 편의 스크립트로 Docker Engine + compose 플러그인 설치
curl -fsSL https://get.docker.com | sudo sh

# 현재 사용자를 docker 그룹에 추가 → 이후 sudo 없이 docker 실행(CD 스크립트 전제)
sudo usermod -aG docker $USER
# 그룹 반영을 위해 로그아웃 후 재접속(또는 `newgrp docker`)
exit
```

재접속 후 확인:

```bash
docker version && docker compose version
```

> [!IMPORTANT]
> GitHub Actions의 배포 스크립트는 `sudo` 없이 `docker compose`를 실행한다. 위 `usermod -aG docker` 를 반드시 적용해 두어야 파이프라인이 성공한다.

---

## 5. DB — mysql-8 컨테이너 + 전용 네트워크

로컬과 동일하게, 앱과 브리지로 묶을 MySQL을 띄운다([[DOCKER]] 모드 B와 같은 구조).

```bash
# 전용 브리지 네트워크(앱↔DB 연결용)
docker network create board-db-net

# MySQL 8 컨테이너 (root/1234, board DB 자동 생성, KST, 데이터 영속)
docker run -d --name mysql-8 \
  --network board-db-net \
  -e MYSQL_ROOT_PASSWORD=1234 \
  -e MYSQL_DATABASE=board \
  -v mysql8-data:/var/lib/mysql \
  mysql:8.0.33 --default-time-zone=+09:00
```

- **`-e MYSQL_DATABASE=board`** 가 빈 `board` 스키마를 만들어 준다(테이블은 앱이 Hibernate `ddl-auto: update`로 생성).
- 데이터는 named volume `mysql8-data`에 남아 컨테이너 재생성에도 보존된다.
- 앱은 같은 네트워크에서 컨테이너명 `mysql-8`로 접속한다(compose가 `DB_HOST: mysql-8` 주입).

---

## 6. 저장소 클론

배포 스크립트가 `~/board`에서 `git pull` 하므로, 그 위치에 클론해 둔다(공개 저장소라 인증 불필요).

```bash
cd ~
git clone https://github.com/icesnake72/boards.git board
cd board
```

---

## 7. 최초 `.env` 생성

카카오 프로퍼티는 fail-fast라 비면 기동이 실패한다([[DOCKER]] §4). 최초 수동 기동을 위해 만들어 둔다(이후 배포에선 GitHub Actions가 Secrets로부터 이 파일을 덮어쓴다).

```bash
cp .env.example .env
nano .env    # KAKAO_REST_API / KAKAO_SECRET / GOOGLE_* 실제 값 입력
```

---

## 8. 최초 수동 기동 (검증)

```bash
docker compose up -d --build      # app + frontend + frontend-react
docker compose ps                 # 세 컨테이너 healthy 확인
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/api/v1/boards   # 200 기대
```

브라우저에서 확인:
- 순수 JS 프론트: `http://3.34.173.34:8070`
- React 프론트: `http://3.34.173.34:8071`

여기까지 성공하면 서버 준비 완료다.

---

## 9. 이후 자동 배포로 전환

서버 세팅이 끝났으면, 코드가 바뀔 때마다 손으로 할 필요가 없다. **GitHub Secrets를 등록**하고 `main`에 push하면 [[CICD-GITHUB-ACTIONS]]의 워크플로(`.github/workflows/deploy.yml`)가:

1. 테스트 실행(H2)
2. Lightsail에 SSH 접속 → `git pull` → `.env` 재생성 → `docker compose up -d --build`

를 자동 수행한다. 필요한 Secret 항목과 등록 방법은 [[CICD-GITHUB-ACTIONS]] 문서를 따른다.

---

## 10. 자주 겪는 문제

| 증상 | 원인·해결 |
|---|---|
| 배포 스크립트에서 `permission denied ... docker.sock` | §4의 `usermod -aG docker` 미적용 → 적용 후 재접속 |
| 앱 기동 즉시 종료 + 카카오 관련 IllegalState | `.env`의 `KAKAO_*`가 비었거나 미해석 → 값 확인([[DOCKER]] §4) |
| 브라우저에서 8070/8071 접속 불가 | Lightsail 방화벽(§2)에 포트 미개방 |
| 앱이 DB 연결 실패(Communications link) | mysql-8 미기동 또는 board-db-net 미연결(§5) |
| 빌드 중 OOM/멈춤 | 인스턴스 메모리 부족(gradle+npm 동시 빌드) → 2GB 이상 플랜 권장 |

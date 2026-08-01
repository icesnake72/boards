# syntax=docker/dockerfile:1

# ── 1) build stage ─────────────────────────────────────────────────────────
# Gradle 래퍼로 실행 가능한 fat jar(bootJar)만 만든다. 로컬에 gradle/JDK가 없어도
# 이미지 안에서 완결적으로 빌드된다. 테스트는 H2로 도는데, 이미지 빌드는 배포 산출물
# 생성이 목적이므로 여기선 `-x test`로 건너뛴다(테스트는 verify-loop/CI에서 별도 수행).
# AS build: 이 스테이지에 "build"라는 이름을 붙인다. 나중 런타임 스테이지에서
# COPY --from=build 로 여기서 만든 jar만 가져오고, JDK·Gradle·소스는 버린다(멀티스테이지의 핵심).
FROM eclipse-temurin:21-jdk AS build

# WORKDIR: 이후 명령(COPY/RUN)의 기준 작업 디렉터리. 없으면 새로 만든다.
# 이 줄 다음의 상대경로 "./"는 모두 /workspace 를 가리킨다(예: COPY ... ./ → /workspace/).
WORKDIR /workspace

# ── 의존성 캐시 레이어 ──────────────────────────────────────────────────────
# [원리] 도커 이미지는 명령(줄)마다 "레이어"로 쌓이고, 각 레이어는 캐시된다.
# 어떤 줄의 입력(COPY 대상 파일 내용 등)이 이전 빌드와 같으면, 도커는 그 줄을
# 다시 실행하지 않고 캐시된 레이어를 재사용한다. 단, 한 줄이라도 캐시가 깨지면
# 그 아래 줄은 전부 다시 실행된다(캐시 무효화가 아래로 전파됨).
#
# [전략] 그래서 "잘 안 바뀌는 것 → 자주 바뀌는 것" 순서로 COPY 한다.
# 의존성 목록(build.gradle 등)은 가끔 바뀌지만 소스(src)는 매번 바뀐다.
# 만약 `COPY . .` 로 전부 한 번에 복사하면, 소스 한 줄만 고쳐도 이 레이어의
# 캐시가 깨져 의존성을 매번 새로 내려받게 된다(느림). 그래서 빌드 스크립트만
# 먼저 복사해 의존성을 받아두고, 소스는 그 다음(아래 COPY src)에서 복사한다.
# → 소스만 바뀐 재빌드에서는 이 의존성 레이어가 캐시로 재사용되어 훨씬 빠르다.

# 1) Gradle 래퍼 실행에 필요한 최소 파일만 복사한다.
#    - gradlew         : Gradle 래퍼 실행 스크립트(로컬에 gradle 설치가 없어도 됨)
#    - settings.gradle : 프로젝트(모듈) 구성 — 래퍼가 프로젝트를 인식하는 데 필요
#    - build.gradle    : 의존성·플러그인 선언 — 이 파일이 바뀌어야 아래 의존성 레이어가 다시 돈다
#    끝의 "./"는 목적지(WORKDIR = /workspace). 여러 파일을 한 디렉터리로 복사할 때 목적지는 "/"로 끝나야 한다.
COPY gradlew settings.gradle build.gradle ./

# 2) 래퍼 배포본(사용할 Gradle 버전·검증 정보)이 담긴 gradle/ 디렉터리 통째 복사.
#    특히 gradle/wrapper/gradle-wrapper.properties 가 어떤 Gradle 버전을 받을지 지정한다.
COPY gradle ./gradle

# 3) 의존성만 미리 내려받아 별도 레이어로 굳힌다(소스 없이 실행 가능한 이유:
#    dependencies 태스크는 컴파일이 아니라 "의존성 해석·다운로드"만 하기 때문).
#    - chmod +x gradlew : 체크아웃/복사 과정에서 실행권한이 빠졌을 경우를 대비해 부여
#    - --no-daemon      : 컨테이너는 한 번 쓰고 버리므로, 백그라운드에 남는 Gradle 데몬을 띄우지 않는다
#                         (데몬은 재사용될 때 이득인데 여기선 재사용이 없어 오히려 자원 낭비)
#    - > /dev/null 2>&1 : 표준출력+표준에러를 버려 빌드 로그를 조용하게(의존성 트리 출력이 장황함)
#    - || true          : 이 단계가 실패해도(네트워크 일시 오류 등) 빌드를 멈추지 않는다.
#                         어차피 진짜 필요한 의존성은 아래 bootJar 단계에서 다시 받으므로,
#                         이 줄은 "캐시 워밍업"일 뿐 실패가 치명적이지 않다.
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 실행 가능 jar 빌드
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ── 2) runtime stage ───────────────────────────────────────────────────────
# JDK가 아닌 JRE만 담아 이미지를 가볍게. 비루트 사용자로 실행하고, 업로드 디렉터리는
# 볼륨 마운트 지점으로 준비한다. 헬스체크용 curl만 최소 설치한다.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system spring \
    && useradd --system --gid spring --home-dir /app spring \
    && mkdir -p /app/uploads

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown -R spring:spring /app

USER spring

# 업로드 저장 루트를 컨테이너 내부 절대경로로 고정(볼륨이 여기에 마운트된다).
# 나머지 비밀값/DB 접속 정보는 compose의 env로 주입한다(이미지에 굽지 않는다).
ENV APP_UPLOAD_DIR=/app/uploads

EXPOSE 8090

# 컨테이너 PID 1이 자바 프로세스가 되도록 exec 형식 사용(SIGTERM 정상 전달 → graceful shutdown)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

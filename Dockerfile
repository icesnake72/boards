# syntax=docker/dockerfile:1

# ── 1) build stage ─────────────────────────────────────────────────────────
# Gradle 래퍼로 실행 가능한 fat jar(bootJar)만 만든다. 로컬에 gradle/JDK가 없어도
# 이미지 안에서 완결적으로 빌드된다. 테스트는 H2로 도는데, 이미지 빌드는 배포 산출물
# 생성이 목적이므로 여기선 `-x test`로 건너뛴다(테스트는 verify-loop/CI에서 별도 수행).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 캐시 레이어: 빌드 스크립트만 먼저 복사해 의존성을 미리 내려받는다.
# src가 바뀌어도 이 레이어는 재사용되어 빌드가 빨라진다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
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

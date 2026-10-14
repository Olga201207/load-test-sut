# syntax=docker/dockerfile:1
#
# 빌드 (loglens-sut 디렉터리에서, EKS 노드가 x86_64 이므로 플랫폼 고정):
#   docker build --platform linux/amd64 -t <repository_url>:<tag> .

# =============================================================================
# 1) 빌드: Gradle wrapper 로 실행 jar 를 만들고 레이어별로 풀어 둔다
# =============================================================================
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# 의존성 해석에 필요한 파일만 먼저 복사한다. 소스만 바뀌면 이 레이어까지는 캐시를 쓴다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Windows 에서 CRLF 로 체크아웃된 gradlew 는 "/bin/sh^M: not found" 로 실패한다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# ~/.gradle (wrapper 배포본, 의존성)은 BuildKit 캐시 마운트에 둔다. 이미지에는 남지 않는다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null

COPY src ./src
# 테스트는 이미지 빌드에서 돌리지 않는다 (로컬에서 gradlew test).
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar -x test

# Spring Boot 레이어(dependencies / spring-boot-loader / snapshot-dependencies / application)로 푼다.
# 코드만 바뀐 재빌드에서는 application 레이어만 새로 push 된다.
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination /extracted

# =============================================================================
# 2) 실행: JRE 만 (alpine)
# =============================================================================
FROM eclipse-temurin:21-jre-alpine

# 숫자 UID 로 실행한다. 쿠버네티스 runAsNonRoot 가 이미지만 보고 root 가 아님을 확인할 수 있다.
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app

WORKDIR /app
# 파일 소유자는 root 로 둔다. 앱 유저는 읽기만 하면 된다.
# 바뀌는 빈도가 낮은 레이어부터 복사한다.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER 10001:10001
EXPOSE 8080

# JVM 옵션. 실행 시 JDK_JAVA_OPTIONS 환경변수로 통째로 바꿀 수 있다 (쿠버네티스에서는 ConfigMap 이 준다).
#   UseG1GC                : CPU 2개 미만 컨테이너에서 JVM 이 SerialGC 로 바뀌는 것을 막는다 (limit 을 바꿔도 GC 가 같게)
#   Initial/MaxRAMPercentage : 컨테이너 메모리 limit 의 60% 를 힙으로. 처음부터 최대로 잡아 측정 중 힙 크기 변화를 없앤다
#   ExitOnOutOfMemoryError : OOM 이면 바로 죽어 쿠버네티스가 재시작하게 한다 (반쯤 죽은 상태로 측정되지 않게)
ENV JDK_JAVA_OPTIONS="-XX:+UseG1GC -XX:InitialRAMPercentage=60 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

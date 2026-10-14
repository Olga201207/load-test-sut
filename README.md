# loglens-sut

loglens 부하 테스트의 샘플 대상(SUT). 비즈니스 로직 없이 **부하 특성을 파라미터로 조절**할 수 있는 엔드포인트만 있다.
Spring Boot 3.5 / Java 21 / Gradle 8.14 (wrapper). DB 는 쓰지 않는다.

명령어는 **Windows CMD** 기준이다.

---

## 1. 엔드포인트

| 경로 | 파라미터 (기본값, 범위) | 동작 | 용도 |
|---|---|---|---|
| `GET /health` | | 즉시 `{"status":"UP"}` | 헬스체크 |
| `GET /echo` | | 즉시 `{"ok":true,"virtual":false}` | 가장 가벼운 경로. 프레임워크 오버헤드 기준선 |
| `GET /delay?ms=100` | `ms` 100, 0 ~ 30000 | `Thread.sleep(ms)` 후 200 | I/O 대기 흉내. 스레드 풀 포화 지점 |
| `GET /cpu?n=1000` | `n` 1000, 1 ~ 1000000 | 32바이트에 SHA-256 을 n 번 연쇄 적용 | CPU 바운드 한계 |
| `GET /payload?kb=100` | `kb` 100, 0 ~ 10240 | `kb` KiB 바이너리 본문 (`application/octet-stream`) | 네트워크 대역폭 영향 |
| `GET /actuator/health` | | Actuator 헬스 (`/liveness`, `/readiness` 하위 경로 포함) | 쿠버네티스 probe |

- 범위를 벗어나거나 숫자가 아니면 **400** 과 `{"error":"ms must be between 0 and 30000: 30001"}` 를 돌려준다.
- `/echo`, `/delay`, `/cpu` 응답의 `virtual` 은 요청을 처리한 스레드가 가상 스레드인지다. 설정이 실제로 먹었는지 이것으로 확인한다.
- `/cpu` 의 n 당 비용은 노드 CPU 에 따라 다르다. 클러스터에서 `n` 을 몇 단계로 바꿔 한 번씩 응답 시간을 재 보고 정한다.
- `/payload` 는 64 KiB 조각을 반복해 쓴다. 요청마다 kb 만큼 메모리를 잡지 않으므로 큰 응답이 몰려도 힙 크기와 무관하다.

---

## 2. 튜닝 파라미터 (환경변수)

`application.yml` 의 값은 모두 환경변수로 덮어쓴다 (Spring relaxed binding: 점·하이픈 → 밑줄, 대문자).
기본값은 `SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS` 를 빼면 Spring Boot / Tomcat 기본값과 같다.
쿠버네티스에서는 ConfigMap 으로 준다 (6절).

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `SPRING_THREADS_VIRTUAL_ENABLED` | `false` | 요청 처리에 가상 스레드 사용 (3절) |
| `SERVER_TOMCAT_THREADS_MAX` | `200` | 플랫폼 스레드 모드의 동시 처리 상한 |
| `SERVER_TOMCAT_THREADS_MIN_SPARE` | `10` | 미리 띄워 둘 스레드 |
| `SERVER_TOMCAT_MAX_CONNECTIONS` | `8192` | 동시에 열어 둘 TCP 연결 수 |
| `SERVER_TOMCAT_ACCEPT_COUNT` | `100` | max-connections 초과 시 OS 대기열 길이 |
| `SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS` | `-1` | 연결 하나당 최대 요청 수. 넘으면 서버가 연결을 닫는다. `-1` 무제한 (Tomcat 기본값은 100) |
| `SERVER_TOMCAT_CONNECTION_TIMEOUT` | `20s` | |
| `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` | `30s` | 종료 시 처리 중 요청을 기다리는 최대 시간 (graceful shutdown) |
| `JDK_JAVA_OPTIONS` | `-XX:+UseG1GC -XX:InitialRAMPercentage=60 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError` | JVM 옵션 (Dockerfile 기본값, k8s 는 ConfigMap). 바꿀 때는 통째로 지정한다 |

> `SERVER_TOMCAT_MAX_KEEP_ALIVE_REQUESTS` 를 Tomcat 기본값 100 으로 두면 k6 의 keep-alive 연결이
> 100 요청마다 끊겨 재연결 비용이 측정에 섞인다. 그래서 기본값을 `-1` 로 바꿨다.

---

## 3. 가상 스레드 on/off 비교

`SPRING_THREADS_VIRTUAL_ENABLED` 는 **앱 전체** 요청 처리 방식을 바꾼다 (`/delay` 만이 아니다).
차이가 가장 크게 드러나는 경로가 `/delay` 다.

| | `false` (기본) | `true` |
|---|---|---|
| 요청 처리 스레드 | 톰캣 플랫폼 스레드 풀 | 요청마다 가상 스레드 |
| `/delay` 중 스레드 | 풀 스레드 하나를 ms 동안 점유 | sleep 동안 캐리어 스레드를 내려놓음 |
| 동시 처리 상한 | `SERVER_TOMCAT_THREADS_MAX` (200) | `SERVER_TOMCAT_MAX_CONNECTIONS` (8192) + `ACCEPT_COUNT` |
| `SERVER_TOMCAT_THREADS_MAX` | 적용 | **무시됨** |

예상: `/delay?ms=100` 에 동시 요청을 늘리면 `false` 는 동시 200 개 근처에서 지연이 계단식으로 늘고,
`true` 는 그 지점이 사라진다. `/cpu` 는 코어 수가 상한이라 두 설정의 차이가 거의 없어야 한다.

로컬 확인 결과 (`SERVER_TOMCAT_THREADS_MAX=4`, `/delay?ms=1000` 동시 8개):

| 설정 | 전체 소요 | 해석 |
|---|---|---|
| `false` | 약 2.3초 | 4개씩 두 번에 나눠 처리 |
| `true` | 약 1.3초 | 8개를 한 번에 처리 |

---

## 4. 로컬 실행과 테스트

```bat
cd /d C:\Project\load-test-module\loglens-sut

:: 단위 테스트 (엔드포인트, 400 검증)
gradlew.bat test

:: 실행 (기본: 플랫폼 스레드, 8080)
gradlew.bat bootRun

:: 가상 스레드로 실행
set SPRING_THREADS_VIRTUAL_ENABLED=true
gradlew.bat bootRun
```

다른 CMD 창에서:

```bat
curl -s http://localhost:8080/echo
curl -s "http://localhost:8080/delay?ms=200"
curl -s "http://localhost:8080/cpu?n=100000"
curl -s -o NUL -w "%{http_code} %{size_download}\n" "http://localhost:8080/payload?kb=1024"
curl -s "http://localhost:8080/delay?ms=99999"
```

> CMD 에서 `&` 가 들어간 URL 은 반드시 큰따옴표로 감싼다. `.bat` 안에서는 `%{...}` 를 `%%{...}` 로 쓴다.

---

## 5. 이미지 빌드와 ECR 푸시

리포지터리는 `loglens-ecr` 스택이 만든다. 로그인, 태그 규칙(IMMUTABLE), 확인 방법은
[`loglens-ecr/README.md`](../loglens-ecr/README.md) 3절을 따른다. 요약:

```bat
set REPO=<AWS_ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/loglens-sut
set TAG=0.1.0

aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin <AWS_ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com

cd /d C:\Project\load-test-module\loglens-sut
docker build --platform linux/amd64 -t %REPO%:%TAG% .
docker push %REPO%:%TAG%
```

### Dockerfile 구조

| 단계 | 베이스 | 하는 일 |
|---|---|---|
| build | `eclipse-temurin:21-jdk-alpine` | Gradle wrapper 로 `bootJar` (테스트 제외), Spring Boot 레이어로 풀기 |
| 실행 | `eclipse-temurin:21-jre-alpine` | JRE 만. 레이어 4개를 변경 빈도 순으로 복사, UID 10001 비 root 로 실행 |

- 의존성 해석 단계를 소스 복사보다 먼저 둬서, 코드만 바뀐 재빌드는 의존성 레이어를 캐시에서 쓴다.
- Gradle 캐시(`~/.gradle`)는 BuildKit 캐시 마운트라 이미지에 남지 않는다 (Docker Desktop 은 BuildKit 이 기본).
- 코드만 바뀌면 `application` 레이어만 새로 push 된다.
- `USER 10001:10001` 은 숫자라서 쿠버네티스 `runAsNonRoot: true` 가 이미지 메타데이터만으로 검사를 통과한다.

---

## 6. 쿠버네티스 배포 (`k8s/`)

kubectl 내장 kustomize 를 쓴다. 별도 도구 설치는 필요 없다.

| 파일 | 내용 |
|---|---|
| `kustomization.yaml` | 네임스페이스 `sut`, **이미지 태그**(`images[].newTag`), **레플리카 수**(`replicas[].count`, 기본 2) |
| `config.env` | 튜닝 값 기준선 → ConfigMap `loglens-sut-config` (고정 이름) |
| `deployment.yaml` | platform 노드, 리소스, probe, 비 root, 읽기 전용 루트 FS |
| `service.yaml` | ClusterIP 8080. **LoadBalancer 금지** (AWS LB 가 생겨 loglens-infra destroy 가 막힌다) |
| `namespace.yaml` | `sut` |

러너가 쓰는 주소: **`http://loglens-sut.sut.svc.cluster.local:8080`**

### 6-1. 배포 / 이미지 교체

```bat
cd /d C:\Project\load-test-module\loglens-sut

:: 렌더링 결과만 보기
kubectl kustomize k8s

kubectl apply -k k8s
kubectl -n sut rollout status deployment/loglens-sut
kubectl -n sut get pods -o wide

:: 클러스터 안에서 확인 (이미지의 busybox wget)
kubectl -n sut exec deploy/loglens-sut -- wget -qO- http://localhost:8080/echo
```

이미지를 바꿀 때는 `kustomization.yaml` 의 `newTag` 를 새 태그로 고치고 `kubectl apply -k k8s`. 파드 템플릿이 바뀌므로 자동으로 롤아웃된다.
되돌릴 때도 같다 (이전 태그로 고치고 apply).

### 6-2. 튜닝 값만 바꾸기 (재배포 없이)

ConfigMap 이름이 고정이라 `kubectl patch` 로 값만 바꾸고 `rollout restart` 하면 된다. 이미지와 매니페스트는 그대로다.

```bat
:: 가상 스레드 켜기
kubectl -n sut patch configmap loglens-sut-config -p "{\"data\":{\"SPRING_THREADS_VIRTUAL_ENABLED\":\"true\"}}"

:: 여러 값을 한 번에
kubectl -n sut patch configmap loglens-sut-config -p "{\"data\":{\"SPRING_THREADS_VIRTUAL_ENABLED\":\"false\",\"SERVER_TOMCAT_THREADS_MAX\":\"50\"}}"

:: 적용 (파드 하나씩 교체, 항상 2개 유지)
kubectl -n sut rollout restart deployment/loglens-sut
kubectl -n sut rollout status deployment/loglens-sut

:: 확인
kubectl -n sut get configmap loglens-sut-config -o yaml
kubectl -n sut exec deploy/loglens-sut -- printenv SERVER_TOMCAT_THREADS_MAX
kubectl -n sut exec deploy/loglens-sut -- wget -qO- http://localhost:8080/echo
```

- 환경변수는 컨테이너가 **시작될 때** 읽힌다. patch 만 하고 restart 하지 않으면 아무것도 바뀌지 않는다.
- `kubectl apply -k k8s` 를 다시 하면 ConfigMap 이 `config.env` 값으로 **되돌아간다** (patch 한 값은 덮어써진다).
  비교 측정 조건을 남겨 두고 싶으면 `config.env` 를 고치고 `apply -k` → `rollout restart` 순서로 한다.
- 설정 변경 롤아웃은 측정 중에 하지 않는다. 롤아웃이 끝나고(`rollout status`) 다음 측정을 시작한다.

### 6-3. 리소스: platform 노드에 얼마나 남나

platform 노드는 m6i.large (2 vCPU / 8 GiB) 2대. EKS AL2023 기준 allocatable 은 **CPU 약 1930m, 메모리 약 7.0 GiB** 로 추정한다
(kube-reserved 와 eviction 여유를 뺀 값). 실제 값은 `kubectl describe nodes -l role=platform` 의 `Allocatable` 과 `Allocated resources` 에서 확인한다.

스케줄러가 보는 것은 **requests** 다. 현재 platform 노드에 올라가는 requests (차트 기본값 + 이 저장소 values 기준):

| 구성요소 | CPU request | 메모리 request | 비고 |
|---|---|---|---|
| DaemonSet (aws-node 25m, kube-proxy 100m) | 125m × 노드 | ≈ 0 | node-exporter, pod-identity-agent 는 request 없음 |
| Prometheus | 500m | 2 GiB | `kube-prometheus-stack.yaml.tpl` |
| CoreDNS × 2 | 100m × 2 | 70 MiB × 2 | |
| k6-operator | 100m | 50 MiB | |
| Grafana, kube-state-metrics, prometheus-operator, Cluster Autoscaler | 0 | 0 | request 미지정. 실제로는 메모리 합계 0.5 GiB 안팎을 쓴다 |
| **loglens-sut × 2** | **500m × 2** | **1 GiB × 2** | limit: CPU 1, 메모리 1 GiB |

| 배치 | 노드 | CPU requests / 1930m | 남는 CPU | 메모리 requests / 7.0 GiB |
|---|---|---|---|---|
| 분산 (topologySpread 가 노림) | Prometheus 노드 + SUT 1 | 125+500+100+100+500 = 1325m | 약 600m | 약 3.1 GiB |
| | 다른 노드 + SUT 1 | 125+100+500 = 725m | 약 1200m | 약 1.1 GiB |
| 최악 (한 노드에 몰림) | Prometheus 노드 + SUT 2 | 1825m | **약 100m** | 약 4.1 GiB |
| 2대 합계 | | 약 2050m / 3860m | **약 1800m** | 약 4.2 / 14 GiB (약 9.8 GiB 남음) |

- 메모리는 넉넉하고 **CPU 가 빠듯한 쪽**이다. 그래서 SUT CPU request 를 500m 로 두어 최악의 배치에서도 들어가게 했다.
- 롤아웃 중 maxSurge 1 로 파드 하나(500m)가 잠깐 더 뜬다. 분산 배치면 어느 노드에도 들어간다.
- requests 는 예약일 뿐이다. 부하 중에는 SUT 가 limit(파드당 1코어)까지 쓴다. 그때 Prometheus 노드에 남는 실제 CPU 는 1코어 안팎이다.
  `/cpu` 로 포화 측정을 할 때는 Grafana 에서 platform 노드 CPU 도 같이 본다. Prometheus 노드가 100% 에 붙으면
  SUT 와 Prometheus 가 서로 측정을 오염시키고 있다는 뜻이다 (그때는 SUT 전용 노드그룹을 검토한다).

### 6-4. JVM 힙: MaxRAMPercentage 60% (고정 -Xmx 대신)

`config.env` 의 `JDK_JAVA_OPTIONS=-XX:+UseG1GC -XX:InitialRAMPercentage=60 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError`

- **비율을 쓰는 이유**: 메모리 limit 하나만 고치면 힙이 따라온다. 고정 `-Xmx` 는 limit 과 두 군데서 따로 관리돼야 하고,
  limit 만 줄이면 OOMKilled, limit 만 늘리면 늘린 메모리를 못 쓴다. 설정을 바꿔 가며 비교할 것이므로 어긋날 여지를 없앴다.
- **75% 가 아니라 60% 인 이유**: 1 GiB limit → 힙 616 MiB, 비힙 여유 약 400 MiB.
  비힙에는 메타스페이스(Spring Boot 약 80~100 MiB), 코드 캐시, GC 자료구조, 스레드 스택(플랫폼 스레드 200개 포화 시 수십~200 MiB),
  `/payload` 의 소켓 버퍼가 들어간다. 75% 면 비힙 여유가 약 256 MiB 라 **포화 측정 중에** 컨테이너가 OOMKilled(exit 137) 될 수 있다.
- **Initial = Max**: 측정 중 힙이 커지면서 생기는 GC 패턴 변화를 없앤다.
- **`-XX:+UseG1GC` 명시**: CPU limit 이 1코어면 JVM 은 자신을 "서버급이 아님"으로 보고 SerialGC 를 고른다.
  CPU limit 을 바꿔 비교할 때 GC 까지 바뀌지 않도록 고정했다.
- **CPU limit 1 의 부수 효과**: JVM 의 `availableProcessors` 가 1 이 된다. 가상 스레드 캐리어도 1개다.
  `/delay` 비교(대기 위주)에는 영향이 없지만, 가상 스레드 모드에서 CPU 작업은 캐리어 하나로 직렬화된다.
  CPU limit 을 2 로 올리거나 `-XX:ActiveProcessorCount=2` 를 `JDK_JAVA_OPTIONS` 에 더하면 2개가 된다.

# 리모리 백엔드

리모리의 API 개발 프로젝트입니다. Java 21, Spring Boot 3.5, Maven Wrapper, MySQL 8.4 LTS를 사용합니다. 로컬에서는 MySQL을 Docker Compose로 실행하고, Spring Boot는 IDE나 터미널에서 실행합니다.

## 로컬 실행

Java 21과 Docker Compose가 필요합니다. Docker Desktop을 실행한 뒤 이 폴더에서 아래 명령을 실행하세요.

```sh
docker compose up -d --wait mysql
./mvnw spring-boot:run
```

다른 터미널에서 API와 DB 연결 상태를 확인할 수 있습니다.

```sh
curl --fail http://127.0.0.1:8080/actuator/health
```

MySQL은 `127.0.0.1:3307`, API는 `127.0.0.1:8080`을 사용합니다. 별도 설정 없이 실행할 수 있습니다. 포트나 개발용 DB 계정 값을 바꿀 때는 이 폴더에서 `.env.example`을 `.env`로 복사해 수정하세요. DB 이름과 계정은 빈 데이터 볼륨을 처음 초기화할 때 생성됩니다. 이미 초기화된 DB의 계정을 바꾸려면 MySQL에서도 값을 변경해야 합니다.

## 데이터베이스 변경

테이블을 처음 추가할 때 `src/main/resources/db/migration/V1__설명.sql`부터 시작하세요. Flyway가 실행 시 적용 이력을 관리하고, Hibernate는 매핑과 스키마를 검증합니다.

`docker compose stop mysql`로 DB를 멈출 수 있습니다. 데이터는 이름이 지정된 Docker 볼륨에 보관되므로 `docker compose down` 후 컨테이너를 다시 만들어도 유지됩니다. `docker compose down -v`는 볼륨과 데이터를 삭제합니다.

개발용 계정, 비밀번호, 로컬 JDBC 설정은 `local` 프로필에서 사용합니다. 운영에서는 별도 활성 프로필과 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 배포 환경에서 지정하세요.

## 빌드

```sh
./mvnw verify
```

첫 빌드에서는 Maven Wrapper가 Maven과 필요한 라이브러리를 내려받습니다.

## 계정과 학습 데이터 API

회원가입과 로그인 요청은 `application/json`으로 `email`과 `password`를 보냅니다. 성공하면 계정 정보, `accessToken`, `expiresAt`을 받습니다. 로그인 이후에는 `Authorization: Bearer <accessToken>` 헤더를 사용합니다. 로그인은 발급일로부터 14일 동안 유지되고, 로그아웃하면 사용 중인 토큰만 폐기합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | 이메일 가입 및 로그인 |
| POST | `/api/v1/auth/login` | 이메일 로그인 |
| GET | `/api/v1/auth/me` | 로그인된 계정 조회 |
| POST | `/api/v1/auth/logout` | 사용 중인 세션 종료 |
| GET | `/api/v1/study` | 현재 계정의 덱·카드·복습 기록과 버전 조회 |
| PUT | `/api/v1/study` | 조회한 버전을 기준으로 학습 데이터 저장 |
| POST | `/api/v1/study/import` | 비회원 데이터를 계정에 추가 |

### 저장과 비회원 이관

- `GET /api/v1/study` 응답은 `{"revision":0,"data":{"decks":[],"cards":[],"logs":[]}}` 형태입니다. `PUT`에는 같은 구조로 최신 `revision`과 변경한 전체 데이터를 보냅니다. 다른 기기에서 이미 저장해 버전이 달라졌다면 `409`가 반환됩니다. 최신 데이터를 다시 읽어 반영할 변경을 합쳐 주세요.
- `POST /api/v1/study/import`에는 리모리의 비회원 데이터인 `{"decks":[...],"cards":[...],"logs":[...]}`를 보냅니다. 같은 ID의 항목은 계정 데이터가 우선이고, 동일한 자료를 다시 보내도 중복되지 않습니다. 카드의 `dueAt`과 `repetitions`, 복습 모드 기록은 새 항목의 원래 값을 유지합니다. 초기 데모에서 생성된 예시 복습 로그는 가져오기에서 제외됩니다.
- 스냅샷 하나에 덱 1,000개, 카드 20,000개, 복습 기록 100,000건까지 저장할 수 있습니다. 카드가 참조하는 덱과 복습 기록이 참조하는 카드는 같은 스냅샷에 있어야 합니다.

현재 API는 로컬 개발용입니다. 외부에서 계정을 받기 전에는 HTTPS와 가입·로그인 요청 제한을 배포 환경에 설정하세요. 이메일 인증, 비밀번호 재설정 메일, 소셜 로그인, 프론트엔드 연동은 후속 작업입니다.

# 리모리 백엔드

리모리의 API 개발 프로젝트입니다. Java 21, Spring Boot 3.5, Spring JDBC, Maven Wrapper, MySQL 8.4 LTS를 사용합니다. 로컬에서는 MySQL을 Docker Compose로 실행하고, Spring Boot는 IDE나 터미널에서 실행합니다.

## 로컬 실행

Java 21과 Docker Compose가 필요합니다. Docker Desktop을 실행한 뒤 이 폴더에서 아래 명령을 실행하세요.

```sh
./mvnw spring-boot:run
```

기본 `local` 프로필에서는 Spring Boot가 Docker Compose로 MySQL을 실행하고, DB 준비가 끝나면 API를 시작합니다. IntelliJ에서도 백엔드 메인 클래스를 실행하면 같은 방식으로 동작합니다. 실행 작업 디렉터리는 이 백엔드 폴더로 지정하세요.

`Ctrl+C`로 API를 종료해도 MySQL은 계속 실행되므로 DataGrip에서 DB를 확인하거나 API를 다시 실행할 수 있습니다. DB까지 멈추려면 `docker compose stop mysql`을 실행하세요.

이미 별도로 실행한 MySQL에 연결할 때는 `SPRING_DOCKER_COMPOSE_ENABLED=false ./mvnw spring-boot:run`으로 자동 실행을 끌 수 있습니다.

다른 터미널에서 API와 DB 연결 상태를 확인할 수 있습니다.

```sh
curl --fail http://127.0.0.1:8080/actuator/health
```

MySQL은 `127.0.0.1:3307`, API는 `127.0.0.1:8080`을 사용합니다. 별도 설정 없이 실행할 수 있습니다. 포트나 개발용 DB 계정 값을 바꿀 때는 이 폴더에서 `.env.example`을 `.env`로 복사해 수정하세요. DB 이름과 계정은 빈 데이터 볼륨을 처음 초기화할 때 생성됩니다. 이미 초기화된 DB의 계정을 바꾸려면 MySQL에서도 값을 변경해야 합니다.

## 데이터베이스 변경

스키마를 변경할 때는 `src/main/resources/db/migration`에 다음 버전의 SQL을 추가하세요. Flyway가 서버 시작 시 변경을 적용하고 이력을 관리합니다. Repository에서 SQL 실행과 결과 매핑을 담당합니다. 단순 조회는 `JdbcClient`로 처리하고, 카드 일괄 등록과 비회원 이관에는 `JdbcTemplate.batchUpdate`를 사용합니다. Service에서 복습 규칙과 트랜잭션을 관리합니다.

`docker compose stop mysql`로 DB를 멈출 수 있습니다. 데이터는 이름이 지정된 Docker 볼륨에 보관되므로 `docker compose down` 후 컨테이너를 다시 만들어도 유지됩니다. `docker compose down -v`는 볼륨과 데이터를 삭제합니다.

개발용 계정, 비밀번호, 로컬 JDBC 설정은 `local` 프로필에서 사용합니다. 운영에서는 별도 활성 프로필과 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 배포 환경에서 지정하세요.

## 빌드

```sh
./mvnw verify
```

첫 빌드에서는 Maven Wrapper가 Maven과 필요한 라이브러리를 내려받습니다.

## Google 로그인 설정

Google Auth Platform에서 웹 애플리케이션 클라이언트를 생성하고 승인된 JavaScript 원본에 `http://localhost`와 `http://localhost:5173`을 등록합니다. 배포 시에는 서비스의 HTTPS 원본도 등록하세요. `GOOGLE_CLIENT_ID`로 인증 대상을 설정하며, 기본값은 이 프로젝트에 등록한 웹 클라이언트입니다.

서버는 Google 공개키로 서명을 검증하고 발급자, 클라이언트 ID, 만료 시각, 검증된 이메일과 nonce를 확인합니다. Google의 `sub`를 `account_identities`에 저장해 같은 사용자를 식별합니다. 로그인과 탈퇴는 각각 5분짜리 일회용 nonce를 사용하며 HttpOnly 쿠키로 해당 브라우저에 연결합니다. 실패한 비밀번호 확인은 트랜잭션을 되돌려 같은 인증 요청에서 다시 확인할 수 있습니다.

기존 이메일 계정은 비밀번호를 확인한 뒤 연결합니다. Google 전용 계정은 비밀번호 없이 생성하며, 탈퇴할 때 Google로 다시 본인 확인을 진행합니다. HTTPS 프록시 뒤에 배포할 때는 전달 헤더 처리(`SERVER_FORWARD_HEADERS_STRATEGY`)를 배포 구성에 맞게 설정해 쿠키에 Secure 속성이 적용되도록 하세요.

Google 관련 단위 테스트는 `./mvnw verify`에서 실행합니다. MySQL 통합 테스트는 테스트 전용 데이터베이스 주소를 지정하면 함께 실행됩니다.

```sh
REMORY_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:13318/remory_google_test?sslMode=DISABLED&allowPublicKeyRetrieval=true' ./mvnw verify
```

테스트 DB 계정은 `REMORY_TEST_DB_USER`, `REMORY_TEST_DB_PASSWORD`로 지정하며 기본값은 `remory`, `remory-local`입니다.

## 계정과 학습 데이터 API

회원가입과 로그인 요청은 `application/json`으로 `email`과 `password`를 보냅니다. 성공하면 계정 정보, `accessToken`, `expiresAt`을 받습니다. 로그인 이후에는 `Authorization: Bearer <accessToken>` 헤더를 사용합니다. 로그인은 발급일로부터 14일 동안 유지되고, 로그아웃하면 사용 중인 토큰만 폐기합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | 이메일 가입 및 로그인 |
| POST | `/api/v1/auth/login` | 이메일 로그인 |
| GET | `/api/v1/auth/google/challenge` | Google 인증용 클라이언트 ID와 일회용 nonce 발급 |
| POST | `/api/v1/auth/google` | Google 가입·로그인 및 기존 이메일 계정 연결 |
| POST | `/api/v1/auth/google/me/delete` | Google 본인 확인 후 로그인된 계정 삭제 |
| POST | `/api/v1/auth/google/account-deletion` | 웹에서 Google 본인 확인 후 계정 삭제 |
| GET | `/api/v1/auth/me` | 로그인된 계정 조회 |
| POST | `/api/v1/auth/logout` | 사용 중인 세션 종료 |
| POST | `/api/v1/auth/me/delete` | 로그인한 계정의 비밀번호를 다시 확인하고 계정 삭제 |
| POST | `/api/v1/auth/account-deletion` | 웹 탈퇴용. 이메일과 비밀번호를 확인하고 계정 삭제 |
| GET | `/api/v1/study` | 현재 계정의 덱과 카드 조회 |
| POST | `/api/v1/study/decks` | 덱 만들기 |
| POST | `/api/v1/study/decks/{deckId}/cards` | 해당 덱에 카드 한 장 또는 여러 장 추가 |
| PATCH | `/api/v1/study/cards/{cardId}` | 카드 내용 수정. 복습 기록과 다음 복습일 보존 |
| DELETE | `/api/v1/study/cards/{cardId}` | 카드와 해당 카드의 복습 기록 삭제 |
| POST | `/api/v1/study/reviews` | 복습 한 번 저장 및 다음 복습 일정 반환 |
| POST | `/api/v1/study/import` | 비회원의 덱·카드·복습 기록 이관 |
| GET | `/api/v1/study/activity` | 지정 기간의 일별 평가 건수 |
| GET | `/api/v1/study/stats` | 덱별 평가, 자주 틀린 카드, 7일 복습 추세 |

### 회원탈퇴

설정에서 탈퇴할 때는 로그인 토큰과 `{"password":"..."}`를 보내며, 웹 탈퇴는 `{"email":"...","password":"..."}`를 보냅니다. Google 전용 계정은 Google 본인 확인 후 삭제하며, 성공하면 계정, 비밀번호 해시, 소셜 로그인 연결, 모든 세션, 덱·카드·복습 기록과 이관 전 학습 스냅샷이 한 트랜잭션에서 삭제됩니다.

### 저장과 비회원 이관

- 학습 데이터는 계정에 종속된 `decks`, `cards`, `review_logs`에 저장합니다. 기존 버전에서 올릴 때는 백엔드를 내린 뒤 새 버전을 실행하세요. Flyway가 저장된 데이터와 ID, 복습 일정을 이관하고 이전 JSON은 `legacy_study_snapshots`에 보관합니다.
- 덱 만들기는 덱 객체, 카드 추가는 `{"cards":[{"id":"...","front":"...","back":"..."}]}`, 복습 저장은 `{"id":"...","cardId":"...","rating":"good","mode":"scheduled"}` 형태를 사용합니다. 같은 ID로 재시도하면 중복 저장하지 않습니다. 정규 복습은 해당 카드의 최근 정규 복습 기록과 실제 복습 간격을 바탕으로 다음 일정을 계산합니다. 자유·집중 복습은 평가 기록만 저장합니다.
- 비회원 이관은 `{"decks":[...],"cards":[...],"logs":[...]}`를 한 트랜잭션으로 처리합니다. 같은 계정에 이미 있는 ID는 서버 데이터를 유지하며, 새 항목의 복습 시각과 다음 복습일은 보존합니다.
- 일별 조회의 `from`과 `to`는 시작일 포함, 종료일 제외입니다. 통계의 `period`는 `all`, `7`, `30` 중 하나이고 `deckId`는 선택 항목입니다. 두 조회 모두 브라우저의 IANA 시간대 `zone`을 사용합니다.

현재 API는 로컬 개발용입니다. 외부에서 계정을 받기 전에는 HTTPS와 인증 요청 제한을 배포 환경에 설정하세요. 프런트엔드는 이 API로 가입·로그인과 학습 기록 저장을 처리합니다. 이메일 인증과 비밀번호 재설정 메일은 후속 작업입니다.

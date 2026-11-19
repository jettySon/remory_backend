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

회원가입과 로그인 요청은 `application/json`으로 `email`과 `password`를 보냅니다. 성공하면 계정 정보, `accessToken`, `expiresAt`을 받습니다. 로그인 이후에는 `Authorization: Bearer <accessToken>` 헤더를 사용합니다. 접근 토큰은 15분간 유효하며 자동 갱신합니다. 서버의 기기별 로그인에는 미사용 기간에 따른 만료를 두지 않습니다. 브라우저의 갱신 쿠키는 400일로 발급하고 갱신할 때마다 다시 연장합니다. 로그아웃하면 현재 기기의 갱신 권한과 그 권한으로 발급한 모든 접근 토큰을 폐기합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | 이메일 가입 및 로그인 |
| POST | `/api/v1/auth/login` | 이메일 로그인 |
| GET | `/api/v1/auth/google/challenge` | Google 인증용 클라이언트 ID와 일회용 nonce 발급 |
| POST | `/api/v1/auth/google` | Google 가입·로그인 및 기존 이메일 계정 연결 |
| POST | `/api/v1/auth/google/me/delete` | Google 본인 확인 후 로그인된 계정 삭제 |
| POST | `/api/v1/auth/google/account-deletion` | 웹에서 Google 본인 확인 후 계정 삭제 |
| POST | `/api/v1/auth/password-reset/request` | 인증코드 요청. 가입 여부와 관계없이 202 및 요청 식별자·만료·재전송 가능 시각 반환 |
| POST | `/api/v1/auth/password-reset/verify` | 요청 식별자와 6자리 코드 확인 후 비밀번호 변경 전용 권한 반환 |
| POST | `/api/v1/auth/password-reset/confirm` | 인증 후 발급받은 일회용 권한으로 비밀번호 변경 및 모든 세션 폐기 |
| GET | `/api/v1/auth/me` | 로그인된 계정 조회 |
| POST | `/api/v1/auth/refresh` | 갱신 쿠키로 접근 토큰 자동 재발급 및 쿠키 교체 |
| POST | `/api/v1/auth/session` | 기존 14일 접근 토큰을 일회성으로 로그인 유지 방식에 이관 |
| POST | `/api/v1/auth/logout` | 현재 기기의 갱신 권한과 접근 토큰 폐기 및 쿠키 삭제 |
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

현재 API는 로컬 개발용입니다. 외부에서 계정을 받기 전에는 HTTPS와 인증 요청 제한을 배포 환경에 설정하세요. 프런트엔드는 이 API로 가입·로그인과 학습 기록 저장을 처리합니다. 회원가입 시 이메일 인증은 후속 작업이며, 비밀번호 재설정은 아래 SMTP 설정 후 사용할 수 있습니다.


## 비밀번호 재설정 메일

메일 발송은 Spring Boot Mail의 `JavaMailSender`를 사용합니다. Gmail 설정 방법은 로컬 개발 메모 `DEV_NOTES.local.md`에 정리하며 실제 인증정보는 Git에서 제외된 `.env`에만 저장합니다. 메일 설정이 없는 상태에서도 API는 실행할 수 있으며, 인증코드 요청에는 503을 반환합니다.

사용자는 앱이나 웹에서 이메일 입력 → 메일의 6자리 코드 입력 → 새 비밀번호 설정 순서로 진행합니다. 메일에는 재설정 URL이나 임시 비밀번호가 들어가지 않습니다. 메일은 파스텔 테마의 HTML과 동일 내용의 일반 텍스트를 함께 제공하고, 구름새 몽이는 메일에 포함한 PNG 이미지로 표시합니다. 외부 이미지 서버나 웹폰트를 사용하지 않습니다. 가입 여부를 노출하지 않도록 조회와 SMTP 발송을 제한된 백그라운드 작업으로 처리하고 동일한 202 응답을 보냅니다. 일반 이메일 계정과 비밀번호가 있는 Google 연결 계정만 코드를 받으며 Google 전용·미가입 계정은 받지 않습니다.

인증코드는 10분간 유효하고 요청별로 최대 5회 확인할 수 있습니다. 재전송은 60초 뒤 가능하며 이메일별 시간당 3회·IP별 시간당 20회 제한도 적용합니다. 서버가 반환하는 `challengeId`는 256비트 난수이며, 코드 해시는 이 값과 함께 계산합니다. DB에는 요청 식별자·코드·변경 권한의 해시만 저장합니다. 코드 추측은 행 잠금으로 직렬화하고 실패한 시도 횟수는 오류 응답 시에도 커밋합니다.

코드 확인 성공 후 발급하는 `resetToken`은 5분간 유효한 비밀번호 변경 전용 일회용 권한입니다. 코드 확인만으로 기존 비밀번호나 로그인 세션은 변경하지 않습니다. 비밀번호 저장 성공 시 다른 인증 요청과 모든 기기의 로그인을 함께 폐기하며, 덱·카드·복습 기록은 유지합니다. 자동 로그인은 하지 않습니다. 새 코드 발송에 성공하면 이전 코드와 변경 권한을 폐기하며, SMTP 실패 시 이전 요청은 유지합니다.

`V7`에서 예전 링크용 임시 테이블을 새 인증코드 테이블로 교체합니다. 기존 링크는 사용할 수 없으므로 새 코드를 요청하세요. 서버를 종료한 뒤 새 버전으로 실행하면 Flyway가 적용합니다. 계정과 학습 데이터는 변경하지 않습니다.

단위 테스트는 `./mvnw verify`로 실행합니다. 별도의 폐기 가능한 MySQL DB를 가리키는 `REMORY_TEST_JDBC_URL`과 테스트 DB 계정을 지정하면 `PasswordResetDatabaseTest`도 실행합니다. 실제 SMTP는 테스트에서 대체합니다. 동시 추측의 5회 제한, 코드·권한 재사용 방지, 재전송과 메일 실패 롤백, 소셜 전용·미가입 차단, 비밀번호 변경 전후 로그인과 학습 기록 보존을 검증합니다.


## 로그인 유지와 자동 갱신

이메일 가입·로그인과 Google 로그인 모두 같은 세션 구조를 사용합니다. 접근 토큰은 15분간 유효하며 자동 갱신합니다. 기기별 로그인과 현재 갱신 권한에는 서버의 미사용 만료를 두지 않고, 로그아웃 또는 보안상 폐기될 때까지 유지합니다. 기존 `account_sessions`는 유지하고 `V8`에서 기기별 `account_refresh_families`, 해시만 보관하는 `account_refresh_tokens`와 세션 간 연결을 추가합니다. `V9`는 아직 유효한 기존 로그인에도 미사용 만료를 없애며, 이미 만료된 권한은 되살리지 않습니다.

브라우저 쿠키는 영구 저장을 보장할 수 없습니다. `remory_refresh`의 Max-Age는 400일이며 갱신마다 연장합니다. 이는 [Chrome의 쿠키 보관 상한](https://developer.chrome.com/blog/cookie-max-age-expires)에 맞춘 값입니다. 브라우저 정책으로 쿠키가 먼저 삭제되거나 앱·사이트 데이터 삭제, 장기 미사용으로 쿠키 만료가 발생하면 다시 로그인해야 합니다. 서버의 무기한 권한과 브라우저의 실제 쿠키 보관 기간은 구분합니다.

`remory_refresh`는 `/api/v1/auth` 경로의 host-only, HttpOnly, SameSite=Lax 쿠키입니다. HTTPS에서는 Secure도 설정합니다. 갱신 원문은 JSON 응답에 포함하지 않고 쿠키로만 전달하며, 서버에는 SHA-256 해시를 저장합니다. 갱신할 때 새 토큰을 발급하고 이전 토큰은 30일간 사용 이력으로 남깁니다. 이 30일은 재사용 탐지 이력 보관 기간이며 로그인 미사용 만료가 아닙니다. 10초 이내 동시 재사용에는 새 인증정보 없이 409 `SESSION_REFRESH_RETRY`를 반환합니다. 그 이후 재사용에는 해당 기기 세션 전체를 폐기합니다. 동시 요청이나 재사용 차단의 결과는 트랜잭션에 반영합니다.

쿠키로 동작하는 `/refresh`, `/session`, `/logout`에는 `X-Remory-Client: web` 헤더가 필요하고 `Sec-Fetch-Site: cross-site` 요청은 거부합니다. 신뢰하지 않는 원본에 CORS를 개방하지 않습니다. 웹과 API는 동일한 HTTPS 원본에서 제공하며, TLS 종료 프록시에서는 신뢰할 프록시의 전달 헤더만 처리하도록 `SERVER_FORWARD_HEADERS_STRATEGY`를 설정해 Secure 쿠키가 유지되게 합니다.

비밀번호 재설정 시에는 모든 기기의 갱신 권한과 접근 토큰을 함께 폐기합니다. 탈퇴하면 연결된 갱신 정보도 외래 키로 함께 삭제됩니다. 일반 로그아웃은 현재 기기 세션만 해제하므로 다른 기기의 로그인은 유지됩니다. 만료된 기록은 주기적으로 정리합니다.

기존 프런트의 탭 저장소에 남아 있는 유효한 14일 토큰은 `/session`에서 한 번만 교환할 수 있습니다. 만료됐거나 이미 새 세션에 속하는 접근 토큰은 이 방식으로 갱신 권한을 만들 수 없습니다. 새 버전을 적용할 때 백엔드를 재시작해 Flyway V8·V9를 적용하세요. 계정과 학습 기록은 보존됩니다.

`SessionDatabaseTest`는 다른 MySQL 통합 테스트와 마찬가지로 별도의 폐기 가능한 `REMORY_TEST_JDBC_URL`을 지정했을 때만 실행합니다. 쿠키만으로 재실행 복원, 장기 미사용 후 갱신, 사용 이력 정리 후 현재 권한 보존, 만료된 접근 토큰 갱신, 동시 갱신·재사용 차단, 기기별 로그아웃, 비밀번호 재설정·탈퇴 시 전체 해제, Google 로그인 유지, 기존 세션 이관을 검증합니다.

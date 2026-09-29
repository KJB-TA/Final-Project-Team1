# 막내on탑 ExpoHub: 박람회 예약·운영 플랫폼

멋사 백엔드 24기 심화 프로젝트입니다. Agile과 MSA로 4주간(2026.08.31 ~ 09.29) 진행한 박람회 탐색, 회차 예약·결제, QR 체크인, 정산까지 잇는 통합 플랫폼입니다.

배포 주소: https://expohub.duckdns.org

## 프로젝트 개요

| 항목 | 내용 |
|---|---|
| 개발 기간 | 2026.08.31 ~ 2026.09.29 (4주, Sprint 1 ~ 4) |
| 팀 구성 | 5인 (김종범, 김태엽, 김태영, 이상민, 정선우) |
| 주요 사용자 | 방문자(박람회를 찾아 회차를 예약하는 회원), 주최자(채널을 만들고 박람회·회차를 운영하는 기획사), 전체관리자(주최자 심사와 플랫폼 정산) |
| 해결하려는 문제 | 방문자: 박람회 정보가 흩어져 있고, 원하는 날짜·조건에 맞는 일정을 직접 맞춰야 함<br>주최자: 회차별 정원·예약자 명단·현장 입장을 따로 관리해야 함<br>관리자: 주최자 승인과 플랫폼 매출·환불 현황을 한곳에서 볼 수단이 없음 |
| 핵심 가치 | 예약부터 입장까지 한 흐름: 회차 예약과 결제, QR 티켓, 현장 체크인<br>정원·결제의 정합성: 정원 조건부 차감, 결제 확정 3갈래(성공·실패·모름) 판정, 웹훅 멱등 처리, 환불 재시도<br>AI로 줄이는 탐색 비용: 자연어 검색, 캘린더 일정 추천, 취향 기반 추천, 박람회 소개글 초안<br>서비스 분리(MSA)와 장애 격리: 서비스별 DB, 내부 API 타임아웃, fail-closed·fail-open 정책 구분 |

## 진입점

* Notion: https://app.notion.com/p/1-on-3c973873401a80eda868e11a916721b8
* GitHub Project: https://github.com/likelion-backend-24th/Final-Project-Team1
* 트러블슈팅 아카이브: https://app.notion.com/p/76a73873401a83de81b301610d58bb16

## 서비스 소개

방문자는 박람회를 찾아 예약하고 입장하고, 주최자는 채널과 박람회·회차를 운영하며 현장 체크인을 처리하고, 전체관리자는 주최자를 심사하고 정산을 봅니다.

### 방문자 (USER)

* 박람회 공개 목록·상세 조회(비회원 포함), 추천순·최신순·모집마감순 정렬, 지역·카테고리·키워드 필터
* 자연어 검색 (Gemini): 예) "다음 주말 서울 IT 행사"
* 회원가입, 소셜 로그인 (Google, Kakao, Naver)
* 회차 예약과 결제 (PortOne), 내 예약·QR 티켓 조회, 예약 취소와 환불(회차 24시간 전까지)
* 캘린더 AI 일정 추천: 기간과 조건("오후만", "IT 위주")을 주면 겹치지 않는 회차 조합 추천
* 관심사 등록, 취향 기반 박람회 추천, 유사 박람회, 개인화 알림
* 마이페이지(닉네임·프로필 사진·비밀번호 변경), 주최자 승격 신청

### 주최자 (ORGANIZER)

* 채널 생성(주최자당 1개), 박람회 등록·수정·공개 전환, AI 소개글 초안 (Gemini)
* 회차 등록·수정·삭제(예약이 없을 때만), 회차별 예약 현황과 예약자 명단 엑셀 다운로드
* 현장 체크인(QR 스캔 또는 예약번호), 체크인 되돌리기, 체크인 결과 요약
* VIP 배너 신청·결제·환불(추천순 상단 노출)

### 전체관리자 (SUPER_ADMIN)

* 주최자 계정 발급, 주최자 신청 심사(승인·반려)
* 정산 대시보드: 일·주·월·연 단위 매출·환불·수수료, 박람회·카테고리 순위, AI 요약

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Data JPA, Flyway |
| Frontend | React 19, TypeScript, Vite, react-router-dom 7 |
| Data | MySQL 8.0 (서비스별 스키마) |
| 외부 연동 | PortOne V2 (결제), Google Gemini (AI), OAuth2 (Google, Kakao, Naver), Cloudinary (이미지) |
| Infra | Docker Compose, Nginx (리버스 프록시·HTTPS), Let's Encrypt, AWS EC2, DuckDNS, GitHub Actions, ghcr.io |
| Test | JUnit 5, Mockito, Testcontainers (MySQL) |

## 아키텍처

```
브라우저
   |
   +-> Nginx :443 (HTTPS, 정적 파일 + API 라우팅)
          -> identity-service        :8081  (MySQL identity)
          -> expo-service            :8082  (MySQL expo)
          -> reservation-service     :8083  (MySQL reservation)
          -> ticket-service          :8084  (MySQL ticket)
          -> recommendation-service  :8085  (MySQL recommendation)
          -> settlement-service      :8086  (자기 DB 없음, 결제 레코드를 조회 시점에 집계)
```

| 서비스 | 책임 |
|---|---|
| identity | 가입, 로그인, 소셜 로그인, 내 프로필, 주최자 신청·심사, 주최자 계정 발급 |
| expo | 채널, 박람회, 공개 상태 관리와 자동 마감, VIP 배너와 배너 결제, 자연어 검색, 소개글 초안 |
| reservation | 회차, 예약, 정원, 예약 결제·환불, 티켓 발급 통지, 캘린더 일정 추천 |
| ticket | QR 티켓, 체크인·되돌리기, 체크인 이력과 현황 |
| recommendation | 관심사, 행동 이벤트, 취향 점수, 박람회 태그, 추천, 알림 |
| settlement | 예약·배너 결제를 Pull 해 기간별 정산 집계 |

| 공통 모듈 | 책임 |
|---|---|
| common-security | JWT 검증(각 서비스가 자체 검증) |
| common-payment | PortOne 연동, 결제 확정·웹훅·환불·환불 재시도(예약·배너 결제가 공유) |
| common-ai | Gemini 호출, 일일 호출 예산 |

서비스는 각자 자기 DB만 사용하고, 서비스 간 호출은 `/internal/v1/**` 내부 API(고정 내부 토큰 인증, 연결 2초·응답 3초 타임아웃)로만 이뤄집니다. Nginx는 `/internal/**` 을 외부에 노출하지 않습니다.

## 프로젝트 구조

```
.
├── backend/                Gradle 멀티모듈 (서비스 6개 + common-security, common-payment, common-ai)
├── frontend/               React + Vite + TypeScript
├── docs/                   Git Snapshot 문서 (Notion 원본 기준)
├── infra/
│   ├── mysql/init.sql      서비스별 스키마 생성
│   ├── nginx/              로컬(nginx.conf)·운영(nginx.prod.conf) 라우팅
│   └── deploy/             운영 환경 변수 예시, HTTPS 인증서 발급 스크립트
├── docker-compose.yml      로컬 인프라 (MySQL, Nginx)
├── docker-compose.prod.yml 운영 배포 (전체 서비스 + web)
└── .github/workflows/      CI (ci.yml), CD (cd.yml)
```

## 로컬 실행

사전 조건: JDK 21, Docker Compose v2, Node.js 22

```bash
# 1. 환경 변수 (DB 비밀번호, JWT·내부 토큰, PortOne 값 등)
cp .env.example .env

# 2. 인프라 (MySQL :13306, Nginx :80)
docker compose up -d

# 3. 백엔드 (서비스별로 실행, 또는 IDE 실행 설정 사용)
#    application-local.yml.example 이 있는 서비스(ticket, settlement)는 application-local.yml 로 복사해 채운다
cd backend
./gradlew :identity-service:bootRun
./gradlew :expo-service:bootRun
./gradlew :reservation-service:bootRun
./gradlew :ticket-service:bootRun
./gradlew :recommendation-service:bootRun
./gradlew :settlement-service:bootRun

# 4. 프론트엔드 (http://localhost:5173, /api 는 Nginx 로 프록시)
cd frontend
cp .env.example .env   # PortOne·소셜 로그인·Cloudinary 공개 키
npm install
npm run dev
```

* 전체관리자 계정은 Flyway 시드(identity V2)로 생성됩니다.
* 박람회는 주최자가 회차를 1개 이상 등록하고 공개(PUBLISHED)해야 목록에 노출됩니다.

## 테스트

```bash
cd backend
./gradlew test                          # 전체
./gradlew :reservation-service:test     # 서비스별
```

* 단위 테스트(Mockito)와 컨트롤러 테스트(@WebMvcTest)는 외부 의존 없이 돌아갑니다.
* 통합 테스트는 Testcontainers 로 MySQL 8.0 컨테이너를 띄우므로 Docker 가 실행 중이어야 합니다.
* 동시성(정원·중복 예약·체크인), 결제 확정 3갈래, 환불 실패·재시도는 테스트로 고정되어 있습니다.

## CI/CD

1. CI: `main` 대상 PR 과 push 에서 백엔드 `./gradlew build`(테스트 포함), 프론트 `npm run lint`·`npm run build` 실행
2. CD: 수동 실행(Actions → CD → Run workflow, `main` 만). 서비스별 이미지와 web 이미지를 ghcr.io 에 push 한 뒤, EC2 에 SSH 로 접속해 `docker compose pull`과 `up -d` 실행

## 문서

| 문서 | 설명 |
|---|---|
| [요구사항정의서](docs/요구사항정의서.md) | 시나리오와 업무 규칙 |
| [화면설계](docs/화면설계.md) | 화면 구성 |
| [서비스 경계](docs/서비스경계.md) | 서비스별 책임과 데이터 소유 |
| [아키텍처](docs/아키텍처.md) | 시스템 구성 |
| [ERD](docs/ERD정의서.md) | 데이터 모델, Migration 소유 |
| [API](docs/API.md) | HTTP와 내부 API 계약 |
| [권한 Matrix](docs/권한매트릭스.md) | 역할별 접근 권한 |
| [시퀀스](docs/시퀀스.md) | 주요 흐름 |
| [테스트 전략](docs/테스트전략.md) | 테스트 수준과 시나리오 |
| [테스트 체크리스트](docs/테스트체크리스트.md) | 테스트 실행 결과 |
| [배포 가이드](docs/배포가이드.md) | 환경 변수, 로컬 실행, 운영 배포 |
| [트러블슈팅](docs/트러블슈팅.md) | 해결한 문제 23건: 증상 → 원인 → 해결 → 검증 → 배운 점, 남은 이슈 |
| [트러블슈팅 아카이브](https://app.notion.com/p/76a73873401a83de81b301610d58bb16) | 문제 발생 → 원인 분석 → 해결 → 배운 점 |

문서 원본은 Notion 이며 Git 의 `docs/` 는 Snapshot 입니다.


## 팀 구성

| 이름 | 역할 |
|-|-|
| 김종범 | 팀장 |
| 김태엽 | 팀원 |
| 김태영 | 팀원 |
| 이상민 | 팀원 |
| 정선우 | 팀원 |

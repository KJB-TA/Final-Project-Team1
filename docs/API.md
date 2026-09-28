# API

# API·통합 계약

> 개정: v.17 / 2026-09-28 — 개발 종료 후 `dev`(PR #331 머지 시점) 코드와 전수 대조. 모든 요청·응답 형태, 오류 코드, 내부 계약을 실제 구현대로 고쳤다. 코드가 문서와 다른 곳은 코드를 기준으로 삼았다.
> 
> 
> 이전 개정과 달라진 큰 것: 오류 코드는 `data.code` 에 있다(최상위 `code` 아님), Refresh Token 은 없다, 소셜 로그인 3종과 마이페이지(닉네임·비밀번호·프로필 사진) 추가, 배너 결제 확인·환불·웹훅 추가, 캘린더·AI 일정 추천·자연어 검색·소개글 초안·체크인 결과 요약 추가, 체크인 되돌리기의 422 삭제, 내부 계약 전체 재작성.
> 

## 공통 사항

| 항목 | 값 |
| --- | --- |
| Base URL (로컬) | http://localhost/api/v1 |
| 인증 방식 | Authorization: Bearer {JWT}. `sub`에 userId, `role`에 역할 문자열 하나(가장 높은 역할) |
| 역할 | `USER`(회원) · `ORGANIZER`(주최자) · `SUPER_ADMIN`(전체관리자). 정확히 일치해야 통과하며 상위 역할이 하위를 포함하지 않는다 |
| Token 수명 | Access Token 1시간. **Refresh Token 은 없다** — 만료되면 다시 로그인한다 |
| 날짜 형식 | `Instant` 는 ISO 8601 UTC `Z` 접미(예: 2026-09-10T10:00:00Z). 정산의 `from`·`to` 만 `yyyy-MM-dd` 문자열 |
| 성공 응답 형식 | `{ "success": true, "data": <본문>, "meta": <페이지 정보 등 또는 null>, "message": null }` |
| 오류 응답 형식 | `{ "success": false, "data": { "code": "ERROR_CODE" }, "meta": null, "message": "설명" }`. **오류 코드는 항상 `data.code` 에 있다** |
| 오류 응답 예외 | Reservation·Ticket·recommendation 은 `meta` 에 `{ "traceId": "…" }` 를 넣는다. Settlement 는 봉투가 아니라 `{ "success": false, "data": { "code" }, "message" }` 이며 `meta` 가 없다 |
| 401 응답 헤더 | `WWW-Authenticate: Bearer` 를 붙인다. expo-service 의 배너 웹훅 401 만 예외 |
| 내부 API 응답 | `/internal/v1/**` 은 성공 응답을 봉투 없이 raw 로 내보낸다. **recommendation-service 내부 API 만 봉투로 감싼다**(호출자가 응답 본문을 읽지 않으므로 무해) |
| 내부 API 경로 | `/internal/v1/…` (배너 결제 조회만 `/internal/expo-promotions/payments`). Nginx 가 외부 노출을 차단한다 |
| 내부 API 인증 | `Authorization: Bearer {INTERNAL_TOKEN}` 환경변수 기반 고정 토큰 하나를 전 서비스가 공유 |
| 내부 호출 Timeout | 연결 2s · 응답 3s (recommendation → expo 는 응답 5s). fail-closed 경로의 실패는 503 `DEPENDENCY_UNAVAILABLE` |
| 페이지네이션 | 박람회 목록·검색: `page`(1부터) · `size`(기본 20, 최대 100), `meta` 에 page·size·totalElements·totalPages. **알림 목록만 `page` 가 0부터**이며 `meta` 대신 본문의 `hasNext` 로 다음 페이지를 판단한다 |
| Trace ID | Nginx 가 `X-Trace-Id` 를 생성해 전달한다. Reservation·Ticket·recommendation 오류 응답의 `meta.traceId` 로 되돌아온다. settlement·recommendation 로의 내부 호출에는 전파되지 않는다 |
| 박람회 상태 | `HIDDEN → PUBLISHED → CLOSED`. `PUBLISHED → HIDDEN` 은 마지막 회차 삭제 시 자동. `CLOSED` 에서 나가는 전이는 없다 |
| 예약 상태 | `PENDING → CONFIRMED / CANCELLED / EXPIRED`. 결제 실패는 CANCELLED, 10분 미결제는 EXPIRED |
| 티켓 상태 | `ISSUED → USED`(체크인) / `ISSUED → CANCELLED`(예약 취소) / `USED → ISSUED`(되돌리기) |
| 배너 상태 | `PENDING → ACTIVE / CANCELLED`, `ACTIVE → EXPIRED`(30일 경과) / `CANCELLED`(환불) |
| 403 / 404 구분 | 타인의 Resource 접근은 403. 단 존재 자체를 숨겨야 하면 404 (비공개 박람회, 타인의 예약, 삭제된 회차, 타인 채널의 박람회 목록·수정·초안). 서비스별 예외는 각 항목에 적었다 |
| 카테고리 | IT·전자, 식품·음료, 패션·뷰티, 교육·취업, 문화·예술, 기타 |
| 예약번호 형식 | `R-XXXX-XXXX` — Crockford Base32 8자, 혼동 문자(I·L·O·U) 제외 |
| QR 토큰 형식 | 32자리 16진수(UUID 에서 하이픈 제거). JWT 가 아니다 |
| 실패 정책 | fail-closed(쓰기·입장 여부 확인) / fail-open(통지·부가 검증) / 부분 실패 허용(조회) |

## 에러 코드

각 서비스가 자기 `ErrorCode` enum 을 따로 가진다. 아래 표의 "소유" 는 그 코드를 실제로 내보내는 서비스다.

| HTTP | 오류 코드 | 설명 | 소유 |
| --- | --- | --- | --- |
| 400 | INVALID_REQUEST | 요청값 형식·범위 오류, 공개 전제 조건 미충족, 파라미터 개수 오류 | 전체 |
| 400 | PAYMENT_AMOUNT_MISMATCH | PG 에서 확인한 결제 금액이 예약·배너 금액과 다름 | Reservation · expo |
| 400 | CANCELLATION_DEADLINE_PASSED | 회차 시작 시각이 지나 취소 불가 | Reservation |
| 401 | UNAUTHENTICATED | 인증 토큰 없음·만료·서명 오류 | 전체 |
| 401 | INVALID_CREDENTIALS | 이메일·비밀번호 불일치, 현재 비밀번호 불일치 | Identity |
| 401 | SOCIAL_LOGIN_FAILED | 소셜 제공자 토큰·코드 검증 실패 (aud 불일치, 이메일 미검증 포함) | Identity |
| 403 | FORBIDDEN | 권한 없음 (역할 불일치, 타인의 채널·박람회·알림) | 전체 |
| 404 | NOT_FOUND | 리소스 없음, 비공개·마감 상태, 타인의 예약, 삭제된 회차 | 전체 |
| 409 | DUPLICATE_EMAIL | 이메일 중복 가입 | Identity |
| 409 | DUPLICATE_NICKNAME | 닉네임 중복 | Identity |
| 409 | DUPLICATE_CHANNEL_NAME | 채널명 중복 | expo |
| 409 | CHANNEL_ALREADY_EXISTS | 이미 채널 보유 | expo |
| 409 | PROMOTION_ALREADY_EXISTS | ACTIVE 상태의 VIP 배너가 이미 존재 | expo |
| 409 | PROMOTION_SLOT_FULL | VIP 배너 슬롯이 가득 참 (기본 10) | expo |
| 409 | PROMOTION_EXPO_NOT_PUBLISHED | 공개 상태가 아닌 박람회로 배너 신청 | expo |
| 409 | CAPACITY_EXCEEDED | 잔여 정원 초과 | Reservation |
| 409 | DUPLICATE_RESERVATION | 동일 회차에 유효한 예약 이미 존재 (DB UNIQUE 로 보장) | Reservation |
| 409 | RESERVATION_CLOSED | 회차가 이미 시작되어 예약 마감 | Reservation |
| 409 | ROUND_HAS_RESERVATIONS | 활성 예약이 있는 회차의 수정·삭제 시도 | Reservation |
| 409 | ROUND_ALREADY_STARTED | 이미 시작한 회차의 수정·삭제 시도 | Reservation |
| 409 | ALREADY_CHECKED_IN | 이미 현장 입장이 완료된 예약의 취소 시도 | Reservation |
| 409 | INVALID_STATE_TRANSITION | 허용되지 않는 상태 전이 (예약·박람회·주최자 신청) | Reservation · expo · Identity |
| 409 | CONFLICT | 이미 체크인·취소된 티켓의 재처리 (recommendation 은 내부 점수 재계산 중복 실행에만) | Ticket · recommendation |
| 409 | CHECKIN_NOT_OPEN | 체크인 시간창이 아직 열리지 않음 | Ticket |
| 409 | CHECKIN_CLOSED | 회차가 종료되어 체크인 불가 | Ticket |
| 429 | RATE_LIMITED | AI 일정 추천 호출 초과 (회원당 분당 5회) | Reservation |
| 503 | DEPENDENCY_UNAVAILABLE | 내부 호출·PG·소셜 제공자 Timeout·실패 (fail-closed) | Identity · expo · Reservation · Ticket |
| 500 | INTERNAL_ERROR | 예상하지 못한 서버 오류 | 전체 |

> Ticket·recommendation 은 매핑되지 않은 예외(예: 잘못된 enum 값)를 400 이 아니라 500 `INTERNAL_ERROR` 로 낸다. Settlement 는 `ResponseStatusException` 만 위 형식으로 내고, 그 외 오류는 Spring 기본 JSON 이다.
> 

---

## 외부 HTTP 계약

### Identity-Service

#### signUp — POST /api/v1/auth/signup

회원가입. 역할은 항상 `USER` 다 — 요청에 `role` 이 없다. 주최자는 `applyOrganizer` 승인 또는 `createOrganizer` 발급으로만 된다.

**Request**

```json
{ "email": "user@example.com", "password": "P@ssw0rd1", "name": "홍길동" }
```

| 필드 | 타입 | 제약 |
| --- | --- | --- |
| email | String | RFC 5322, 최대 255자, 필수. 앞뒤 공백 제거·소문자 변환해 저장. `@social.expohub.local` 도메인은 가입 불가 |
| password | String | 8~64자, 영문·숫자 각 1자 이상, 필수 |
| name | String | 최대 100자, 필수. 실명이며 중복 허용 |

**Response 201**

```json
{ "userId": 42, "email": "user@example.com", "name": "홍길동", "role": "USER" }
```

> **닉네임은 자동으로 만든다.** 이름을 그대로 쓰되 누군가의 닉네임과 겹치면 `이름#1234`(4자리 난수) 를 붙인다. 응답에는 없고 `GET /users/me` 에서 본다.
> 

**Errors** 400 INVALID_REQUEST, 409 DUPLICATE_EMAIL

---

#### login — POST /api/v1/auth/login

**Request**

```json
{ "email": "user@example.com", "password": "P@ssw0rd1" }
```

**Response 200**

```json
{ "accessToken": "eyJhbGci...", "tokenType": "Bearer", "expiresAt": "2026-09-28T11:00:00Z" }
```

**Errors** 401 INVALID_CREDENTIALS(이메일 없음·비밀번호 불일치·소셜 전용 계정 모두 같은 코드)

> Refresh 엔드포인트는 없다. 프론트는 `expiresAt` 을 보고 만료 전 재로그인을 안내한다.
> 

---

#### googleLogin — POST /api/v1/auth/google

구글 팝업에서 받은 액세스 토큰으로 로그인·가입한다.

**Request**

```json
{ "googleAccessToken": "ya29...." }
```

**Response 200** — `login` 과 동일

**검증 순서** tokeninfo 로 `aud` 가 우리 `GOOGLE_CLIENT_ID` 인지 확인(환경변수가 비어 있으면 무조건 거부) → `verified_email` 확인 → userinfo 로 이메일·이름 조회 → 같은 이메일 회원이 있으면 연결, 없으면 `USER` 로 생성.

**Errors** 401 SOCIAL_LOGIN_FAILED(aud 불일치·미검증 이메일·토큰 무효), 503 DEPENDENCY_UNAVAILABLE(구글 응답 없음 — 연결 2s·응답 3s)

---

#### naverLogin — POST /api/v1/auth/naver

**Request**

```json
{ "code": "인가코드", "state": "요청 때 보낸 state" }
```

**Response 200** — `login` 과 동일. 검증·오류는 구글과 같다.

---

#### kakaoLogin — POST /api/v1/auth/kakao

**Request**

```json
{ "code": "인가코드", "redirectUri": "https://expohub.duckdns.org/oauth/kakao" }
```

**Response 200** — `login` 과 동일

> 카카오가 이메일을 주지 않거나 `is_email_verified` 가 false 면 `kakao_{id}@social.expohub.local` 자리표시 이메일로 가입한다. 이 도메인으로는 이메일 가입·로그인이 막혀 있어 다른 사람이 그 계정을 가로챌 수 없다.
> 

---

#### createOrganizer — POST /api/v1/admin/organizers

`SUPER_ADMIN` 전용. 주최자 계정을 발급한다.

**Request**

```json
{ "email": "host@example.com", "password": "Temp1234", "name": "행사기획사" }
```

| 필드 | 제약 |
| --- | --- |
| email | 최대 255자, 필수 |
| password | 8~64자, 영문·숫자 각 1자 이상, 필수 (필드명이 `temporaryPassword` 가 아니라 `password` 다) |
| name | 필수 |

**Response 201**

```json
{ "userId": 43, "email": "host@example.com", "name": "행사기획사", "role": "ORGANIZER" }
```

**Errors** 400 INVALID_REQUEST, 403 FORBIDDEN, 409 DUPLICATE_EMAIL

---

#### applyOrganizer — POST /api/v1/me/organizer-applications

로그인한 누구나 호출할 수 있다(역할 검사 없음). 주최자 승격을 신청한다.

**Request** (본문 선택)

```json
{ "reason": "IT 박람회 전문 기획사 운영 중입니다." }
```

**Response 201**

```json
{
  "id": 1,
  "userId": 42,
  "status": "PENDING",
  "reason": "IT 박람회 전문 기획사 운영 중입니다.",
  "rejectReason": null,
  "reviewerId": null,
  "reviewedAt": null,
  "createdAt": "2026-09-16T14:00:00Z"
}
```

**Errors** 409 INVALID_STATE_TRANSITION(PENDING 신청이 이미 있음, **또는 이미 ORGANIZER** — 403 이 아니다)

---

#### getMyApplication — GET /api/v1/me/organizer-applications

내 최근 신청 1건.

**Response 200** — `applyOrganizer` 와 동일 형태

**Errors** 404 NOT_FOUND(신청 이력 없음)

---

#### listOrganizerApplications — GET /api/v1/admin/organizer-applications

`SUPER_ADMIN` 전용. PENDING 신청 목록. 관리자 목록에만 `userName`·`userEmail` 이 추가로 실린다.

**Response 200**

```json
[
  { "id": 1, "userId": 42, "userName": "홍길동", "userEmail": "user@example.com", "status": "PENDING", "reason": "…", "rejectReason": null, "reviewerId": null, "reviewedAt": null, "createdAt": "2026-09-16T14:00:00Z" }
]
```

---

#### approveApplication — PATCH /api/v1/admin/organizer-applications/{id}/approve

`SUPER_ADMIN` 전용. 승인하고 ORGANIZER 역할을 부여한다. 회원의 JWT 는 다음 로그인부터 새 역할을 담는다.

**Response 200** — `status: "APPROVED"`, `reviewerId`, `reviewedAt` 채워진 신청 객체

**Errors** 404 NOT_FOUND, 409 INVALID_STATE_TRANSITION(이미 APPROVED·REJECTED)

---

#### rejectApplication — PATCH /api/v1/admin/organizer-applications/{id}/reject

`SUPER_ADMIN` 전용.

**Request** (선택)

```json
{ "rejectReason": "서류 미첨부" }
```

**Response 200** — `status: "REJECTED"` 와 `rejectReason` 포함

**Errors** 404 NOT_FOUND, 409 INVALID_STATE_TRANSITION(이미 APPROVED·REJECTED)

---

#### getMe — GET /api/v1/users/me

로그인한 누구나. 마이페이지용 내 정보.

**Response 200**

```json
{
  "id": 42,
  "email": "user@example.com",
  "name": "홍길동",
  "nickname": "홍길동#4821",
  "role": "USER",
  "profileImageUrl": "https://res.cloudinary.com/.../me.jpg",
  "hasPassword": true
}
```

| 필드 | 설명 |
| --- | --- |
| name | 실명. 바꿀 수 없다 |
| nickname | 유일한 표시 이름. 마이페이지에서 바꾼다 |
| hasPassword | false 면 소셜로만 가입한 계정이다. 화면이 비밀번호 변경 메뉴를 숨긴다 |

---

#### checkNicknameAvailability — GET /api/v1/users/me/nickname-availability?nickname=

로그인한 누구나. 내 현재 닉네임과 같으면 `true` 다.

**Response 200**

```json
{ "available": false }
```

---

#### changeNickname — PATCH /api/v1/users/me/nickname

**Request**

```json
{ "nickname": "길동이" }
```

| 필드 | 제약 |
| --- | --- |
| nickname | 공백 불가, 최대 100자 |

**Response 200** — `getMe` 와 동일 형태

**Errors** 400 INVALID_REQUEST, 409 DUPLICATE_NICKNAME

---

#### changePassword — PATCH /api/v1/users/me/password

**Request**

```json
{ "currentPassword": "P@ssw0rd1", "newPassword": "N3wPassw0rd" }
```

**Response 200** — `data: null`

**Errors** 400 INVALID_REQUEST(새 비밀번호 규칙 위반), 401 INVALID_CREDENTIALS(현재 비밀번호 불일치·소셜 전용 계정)

---

#### changeProfileImage — PATCH /api/v1/users/me/profile-image

프론트가 Cloudinary 에 먼저 올리고 주소만 보낸다. 서버는 파일을 받지 않는다.

**Request**

```json
{ "imageUrl": "https://res.cloudinary.com/.../me.jpg" }
```

| 필드 | 제약 |
| --- | --- |
| imageUrl | 공백 불가, 최대 500자 |

**Response 200** — `getMe` 와 동일 형태

---

### expo-service

#### createChannel — POST /api/v1/channels

`ORGANIZER` 전용. 주최자당 1개 제한.

**Request**

```json
{ "name": "글로벌테크채널", "description": "IT·스타트업 박람회 전문 채널" }
```

**Response 201**

```json
{ "id": 1, "name": "글로벌테크채널", "description": "IT·스타트업 박람회 전문 채널", "ownerId": 43, "createdAt": "2026-09-01T09:00:00Z" }
```

**Errors** 403 FORBIDDEN(역할), 409 CHANNEL_ALREADY_EXISTS, 409 DUPLICATE_CHANNEL_NAME

> 같은 이름으로 동시에 만들면 DB UNIQUE 에 걸려 409 가 아니라 500 이 난다. 알려진 제한.
> 

---

#### getMyChannel — GET /api/v1/channels/my

`ORGANIZER` 전용.

**Response 200** — 채널 객체

**Errors** 403 FORBIDDEN, 404 NOT_FOUND(채널 미보유)

---

#### getChannel — GET /api/v1/channels/{channelId}

로그인한 누구나. 채널 공개 정보.

**Response 200** — 채널 객체

**Errors** 404 NOT_FOUND

---

#### createExpo — POST /api/v1/channels/{channelId}/expos

`ORGANIZER` 전용. 초기 상태는 `HIDDEN`.

**Request**

```json
{
  "title": "2026 글로벌 IT 박람회",
  "description": "최신 IT 트렌드를 한눈에",
  "venue": "서울 코엑스 A홀",
  "region": "서울",
  "category": "IT·전자",
  "thumbnailUrl": "https://res.cloudinary.com/.../cover.jpg",
  "detailImageUrls": ["https://res.cloudinary.com/.../d1.jpg"]
}
```

| 필드 | 타입 | 제약 |
| --- | --- | --- |
| title | String | 필수, 최대 200자 |
| description | String | 선택. 줄바꿈 보존 |
| venue | String | 선택, 최대 200자 |
| region | String | 선택, 최대 50자 |
| category | String | 정해진 6개 중 하나, 필수 |
| thumbnailUrl | String | 선택, 최대 500자. 빈 문자열 또는 `http(s)://` 로 시작 |
| detailImageUrls | String[] | 선택, 최대 20개, 각 500자 이하 `http(s)://`. 등록 순서를 유지한다 |

**Response 201** — expo 객체

```json
{
  "id": 12,
  "channelId": 1,
  "title": "2026 글로벌 IT 박람회",
  "description": "최신 IT 트렌드를 한눈에",
  "venue": "서울 코엑스 A홀",
  "region": "서울",
  "category": "IT·전자",
  "status": "HIDDEN",
  "thumbnailUrl": "https://res.cloudinary.com/.../cover.jpg",
  "detailImageUrls": ["https://res.cloudinary.com/.../d1.jpg"],
  "createdAt": "2026-09-01T09:00:00Z",
  "updatedAt": "2026-09-01T09:00:00Z"
}
```

> 주최자 화면(등록·목록·상세·수정)은 PK 를 `id` 로, 공개 목록·상세는 `expoId` 로 내려준다. 프론트가 한 곳에서 흡수한다.
> 

**Errors** 400 INVALID_REQUEST, 403 FORBIDDEN(역할), 404 NOT_FOUND(타인의 채널)

---

#### listExposForOrganizer — GET /api/v1/channels/{channelId}/expos

`ORGANIZER` 전용. 본인 채널의 박람회 목록. `HIDDEN`·`CLOSED` 도 보인다.

**Response 200** — expo 객체 배열

**Errors** 403 FORBIDDEN(역할), 404 NOT_FOUND(타인의 채널)

---

#### getExpoForOrganizer — GET /api/v1/channels/{channelId}/expos/{expoId}

`ORGANIZER` 전용. 본인 채널이면 `HIDDEN`·`CLOSED` 도 200.

**Response 200** — expo 객체

**Errors** 404 NOT_FOUND(타인의 박람회)

---

#### updateExpo — PATCH /api/v1/channels/{channelId}/expos/{expoId}

`ORGANIZER` 전용. 변경 항목만 보낸다. 모든 필드가 선택이며 제약은 `createExpo` 와 같다.

**Request**

```json
{ "description": "수정된 소개문", "venue": "부산 BEXCO", "detailImageUrls": ["https://res.cloudinary.com/.../d1.jpg"] }
```

**Response 200** — 수정된 expo 객체

**Errors** 400 INVALID_REQUEST, 404 NOT_FOUND

> `detailImageUrls` 를 보내면 기존 목록을 통째로 교체한다.
> 

---

#### draftDescription — POST /api/v1/channels/{channelId}/expos/description-draft

`ORGANIZER` 전용. 키워드로 소개글 초안을 만든다(AI-4). 저장하지 않는다.

**Request**

```json
{ "keywords": ["AI", "스타트업", "네트워킹"], "title": "2026 글로벌 IT 박람회", "category": "IT·전자", "venue": "서울 코엑스", "region": "서울" }
```

| 필드 | 제약 |
| --- | --- |
| keywords | 1~10개, 각 50자 이하, 필수. 공백 항목은 버린다 |
| title · category · venue · region | 선택. 각 200 · 50 · 200 · 50자 이하 |

**Response 200**

```json
{ "description": "…생성된 소개문…", "applied": true }
```

> Gemini 키가 없거나 호출이 실패하면 `{ "description": null, "applied": false }` 로 200 이다(fail-open). 화면은 "지금은 초안을 만들 수 없다" 로 안내한다.
> 

**Errors** 400 INVALID_REQUEST, 403 FORBIDDEN(역할), 404 NOT_FOUND(타인의 채널)

---

#### publishExpo — POST /api/v1/expos/{expoId}/publication

`ORGANIZER` 전용. `HIDDEN → PUBLISHED`. 살아있는 회차가 하나 이상 있어야 한다. 성공 후 recommendation-service 에 태깅 이벤트를 보낸다(fail-open).

**Response 200**

```json
{ "expoId": 12, "status": "PUBLISHED" }
```

**Errors** 400 INVALID_REQUEST(회차 없음), 403 FORBIDDEN(타인의 박람회), 409 INVALID_STATE_TRANSITION(CLOSED 재공개), 503 DEPENDENCY_UNAVAILABLE(회차 존재 조회 실패 — HIDDEN 유지)

---

#### listExpos — GET /api/v1/expos

공개된 박람회 목록. 인증 불필요.

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| region | String | — | 지역 일치 |
| category | String | — | 카테고리 일치. 목록에 없는 값은 400 |
| keyword | String | — | 제목·소개문·장소 부분 일치 |
| sort | String | recommended | recommended(등록 최신순) · newest(등록 최신순) · deadline(모집마감일순) |
| page | Integer | 1 | 페이지 번호(1부터) |
| size | Integer | 20 | 페이지 크기(최대 100, 초과는 100 으로 자름) |

**Response 200**

```json
{
  "success": true,
  "data": [
    {
      "expoId": 12,
      "channelId": 1,
      "title": "2026 글로벌 IT 박람회",
      "venue": "서울 코엑스 A홀",
      "region": "서울",
      "category": "IT·전자",
      "thumbnailUrl": "https://res.cloudinary.com/.../cover.jpg",
      "createdAt": "2026-09-01T09:00:00Z",
      "paid": true
    }
  ],
  "meta": { "page": 1, "size": 20, "totalElements": 42, "totalPages": 3 },
  "message": null
}
```

| 필드 | 값 | 의미 |
| --- | --- | --- |
| paid | true | 예약 가능한 회차 중 참가비가 있는 회차가 하나라도 있다 |
| paid | false | 예약 가능한 회차가 모두 무료다 |
| paid | null | 판정할 수 없다 — 예약 가능한 회차가 없거나 회차 조회에 실패했다. 화면은 배지를 숨긴다 |

> **recommended 와 newest 는 현재 같은 정렬(createdAt DESC)이다.** VIP 배너는 목록 정렬에 섞지 않고 `getActivePromotions` 를 프론트가 따로 얹는다.
> 
> 
> **deadline 정렬은 Reservation-Service 가 매긴다.** 필터에 맞는 공개 박람회 id 전부를 `deadlineSort` 내부 호출로 넘겨 가장 가까운 회차 종료 시각 순으로 페이지를 받는다. 실패하면 503 `DEPENDENCY_UNAVAILABLE` 이다(fail-closed) — 정렬이 틀린 목록을 맞는 것처럼 주지 않는다.
> 

**Errors** 400 INVALID_REQUEST(허용 범위 밖 카테고리), 503 DEPENDENCY_UNAVAILABLE(deadline 정렬 조회 실패)

---

#### getExpo — GET /api/v1/expos/{expoId}

박람회 상세 + 회차 목록. 인증 불필요. `PUBLISHED` 만 조회된다.

**Response 200**

```json
{
  "expoId": 12,
  "channelId": 1,
  "title": "2026 글로벌 IT 박람회",
  "description": "...",
  "venue": "서울 코엑스 A홀",
  "region": "서울",
  "category": "IT·전자",
  "status": "PUBLISHED",
  "thumbnailUrl": "https://res.cloudinary.com/.../cover.jpg",
  "createdAt": "2026-09-01T09:00:00Z",
  "detailImageUrls": ["https://res.cloudinary.com/.../d1.jpg"],
  "roundsAvailable": true,
  "rounds": [
    { "roundId": 68, "sequence": 1, "startsAt": "2026-09-10T01:00:00Z", "endsAt": "2026-09-10T09:00:00Z", "capacity": 200, "remaining": 87, "fee": 15000 }
  ]
}
```

| 필드 | 설명 |
| --- | --- |
| sequence | 날짜순 회차 번호(1부터). 화면에 "1회차" 로 표시한다 |
| roundsAvailable | false 면 회차 조회에 실패한 것이다. `rounds` 는 null 이고 박람회 기본 정보는 정상이다 |

**Errors** 404 NOT_FOUND(HIDDEN·CLOSED 직접 조회)

---

#### searchExpos — GET /api/v1/expos/search

자연어 검색(AI-5). 인증 불필요. "LLM 은 필터를 만들고 목록은 DB 가 만든다."

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| q | String | — | 자연어 질의, 필수 |
| ignore | String[] | — | 방문자가 지운 해석 칩. region · category · paid · date · keyword 반복 파라미터 |
| page · size | Integer | 1 · 20 | `listExpos` 와 같다 |

**Response 200**

```json
{
  "success": true,
  "data": {
    "interpreted": { "region": "서울", "category": "IT·전자", "paid": false, "dateFrom": "2026-10-01", "dateTo": "2026-10-31", "keyword": null },
    "aiApplied": true,
    "expos": [ { "expoId": 12, "…": "listExpos 의 항목과 동일" } ]
  },
  "meta": { "page": 1, "size": 20, "totalElements": 3, "totalPages": 1 }
}
```

| 필드 | 설명 |
| --- | --- |
| interpreted | `ignore` 로 지운 조건을 뺀 뒤의 해석. 화면이 칩으로 보여준다 |
| aiApplied | false 면 Gemini 가 실패해 규칙 해석(지역·카테고리·유료 키워드 매칭)으로 물러난 것이다 |
| dateFrom · dateTo | `yyyy-MM-dd`, KST 기준. 기간 조건은 회차 시각을 가진 Reservation-Service 에 `by-date` 로 물어 거른다. 그 호출이 실패하면 503 이다(fail-closed) — 날짜와 무관한 결과를 섞어 주지 않는다. 이미 마감된 회차는 제외한다 |

---

#### applyPromotion — POST /api/v1/expo-promotions

`ORGANIZER` 전용. VIP 배너를 신청하고 PG 에 9,900원 결제를 사전 등록한다. 결제창은 프론트가 띄운다.

**Request**

```json
{ "expoId": 12 }
```

**Response 201**

```json
{ "promotionId": 3, "expoId": 12, "amount": 9900, "paymentId": "promo-3-…", "status": "PENDING" }
```

**Errors** 403 FORBIDDEN(타인의 박람회), 404 NOT_FOUND, 409 PROMOTION_EXPO_NOT_PUBLISHED, 409 PROMOTION_SLOT_FULL(ACTIVE 가 슬롯 수 이상), 409 PROMOTION_ALREADY_EXISTS(ACTIVE 배너 있음), 503 DEPENDENCY_UNAVAILABLE(PG 사전 등록 실패)

> 남아 있던 PENDING 신청은 새 신청 때 자동으로 정리한다. 그 PENDING 이 실제로 결제돼 있었으면 환불한다.
> 

---

#### confirmPromotionPayment — POST /api/v1/expo-promotions/{promotionId}/payment

`ORGANIZER` 전용. 결제창이 닫힌 뒤 화면이 부른다. 서버가 PG 에 직접 조회한 결과로만 배너를 켠다. 요청 본문 없음.

**Response 200**

```json
{ "promotionId": 3, "status": "ACTIVE" }
```

| PG 결과 | 처리 |
| --- | --- |
| 결제 완료 · 금액 일치 | 슬롯을 다시 세어 자리가 있으면 ACTIVE(30일), 없으면 환불하고 CANCELLED 로 200 |
| 결제 실패 확정 | CANCELLED 로 200 |
| 금액 불일치 | 400 PAYMENT_AMOUNT_MISMATCH |
| 결과 모름 | 503 DEPENDENCY_UNAVAILABLE — 상태 유지, 다시 부르면 된다 |
| 이미 ACTIVE | 재검증 없이 200(멱등) |

**Errors** 400 PAYMENT_AMOUNT_MISMATCH, 403 FORBIDDEN, 404 NOT_FOUND, 409 INVALID_STATE_TRANSITION(CANCELLED·EXPIRED), 503 DEPENDENCY_UNAVAILABLE

> 활성화 직전 슬롯 재확인은 잠금 없이 센다. 마지막 한 자리를 두고 두 주최자가 동시에 확인하면 슬롯을 1 넘길 수 있다. 알려진 제한.
> 

---

#### refundPromotion — POST /api/v1/expo-promotions/{promotionId}/refund

`ORGANIZER` 전용. ACTIVE 배너를 내리고 환불한다. 요청 본문 없음.

**Response 200** — `data: null`

**Errors** 403 FORBIDDEN, 404 NOT_FOUND, 409 INVALID_STATE_TRANSITION(ACTIVE 가 아님)

> **배너는 환불 결과와 무관하게 먼저 내린다.** PG 환불이 실패해도 200 이며, 결제 행에 실패를 기록하고 재시도 배치가 처리한다. 응답에 환불 상태가 없으므로 화면은 "환불 처리 중" 으로만 안내한다.
> 

---

#### promotionWebhook — POST /api/v1/expo-promotions/webhooks/portone

PortOne 이 부른다. 인증 대신 서명 검증.

**Headers** `webhook-id`, `webhook-signature`, `webhook-timestamp` — Raw Body 기준 HMAC 검증

**Response** 200 처리 완료(중복·무관 이벤트 포함), 401 서명 불일치, 503 PG 재조회 결과 모름(RECEIVED 로 남겨 재전송을 받는다)

> 결제 확인 화면 호출과 웹훅은 같은 `applyPaymentResult` 로 수렴한다. 웹훅이 먼저 오면 화면 호출은 멱등 200 이다.
> 

---

#### getActivePromotions — GET /api/v1/expo-promotions/active

현재 노출 중인 VIP 배너. 인증 불필요. ACTIVE 이면서 박람회가 `PUBLISHED` 인 것만.

**Response 200**

```json
[
  { "promotionId": 3, "expoId": 12, "title": "2026 글로벌 IT 박람회", "thumbnailUrl": "https://…", "region": "서울", "category": "IT·전자", "paidAt": "2026-09-08T00:00:00Z" }
]
```

---

#### getReservationSummary — GET /api/v1/expos/{expoId}/reservations/summary

`ORGANIZER` 전용. 회차별 정원·확정·취소·체크인 수. Reservation-Service 와 Ticket-Service 를 내부 호출해 합친다.

**Response 200**

```json
{ "expoId": 12, "rounds": [ { "roundId": 68, "capacity": 200, "confirmed": 113, "cancelled": 7, "checkedIn": 42 } ] }
```

| 필드 | 설명 |
| --- | --- |
| checkedIn | `USED` 티켓의 인원 합. Ticket-Service 호출이 실패하면 null 이고 나머지 칸은 정상이다(부분 실패 허용) |

**Errors** 403 FORBIDDEN(역할·타인의 박람회), 404 NOT_FOUND, 503 DEPENDENCY_UNAVAILABLE(예약 집계 조회 실패)

---

#### downloadAttendees — GET /api/v1/expos/{expoId}/reservations/attendees.xlsx?roundId=

`ORGANIZER` 전용. 예약자 명단 엑셀. `roundId` 를 주면 그 회차만.

**Response 200** — `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 바이트. 봉투 없음

> 명단은 개인정보를 담으므로 Log 에 본문을 남기지 않는다.
> 

**Errors** 403 FORBIDDEN, 404 NOT_FOUND, 503 DEPENDENCY_UNAVAILABLE

---

### Reservation-Service

#### createRound — POST /api/v1/expos/{expoId}/rounds

`ORGANIZER` 전용. 박람회 소유권은 expo-service 에 내부 조회해 확인한다.

**Request**

```json
{ "startsAt": "2026-09-10T01:00:00Z", "endsAt": "2026-09-10T09:00:00Z", "capacity": 200, "fee": 15000 }
```

| 필드 | 제약 |
| --- | --- |
| startsAt | 필수. 등록 시점보다 미래 |
| endsAt | 필수. startsAt 보다 늦어야 한다 |
| capacity | 필수, 1 이상 |
| fee | 선택, 0 이상. 없으면 0. 0이면 무료 회차이며 예약이 즉시 확정된다 |

**Response 201** + `Location: /api/v1/expos/{expoId}/rounds/{roundId}`

```json
{ "roundId": 68, "sequence": 1, "startsAt": "2026-09-10T01:00:00Z", "endsAt": "2026-09-10T09:00:00Z", "capacity": 200, "remaining": 200, "fee": 15000 }
```

**Errors** 400 INVALID_REQUEST, 403 FORBIDDEN(역할·타인의 박람회), 404 NOT_FOUND(박람회 없음), 503 DEPENDENCY_UNAVAILABLE(소유권 조회 실패)

---

#### listRounds — GET /api/v1/expos/{expoId}/rounds

`ORGANIZER` 전용. 삭제된 회차는 반환하지 않는다.

**Response 200** — round 객체 배열, `startsAt` 오름차순. `sequence` 는 1부터 순서대로

---

#### updateRound — PATCH /api/v1/expos/{expoId}/rounds/{roundId}

`ORGANIZER` 전용. 활성 예약이 0건이고 아직 시작하지 않은 회차만. **네 필드 모두 필수(전체 교체)** — PATCH 지만 부분 수정이 아니다.

**Request**

```json
{ "startsAt": "2026-09-10T02:00:00Z", "endsAt": "2026-09-10T09:00:00Z", "capacity": 250, "fee": 20000 }
```

**Response 200** — 수정된 round 객체

**Errors** 400 INVALID_REQUEST(필드 누락 포함), 403 FORBIDDEN, 404 NOT_FOUND(삭제된 회차 포함), 409 ROUND_ALREADY_STARTED, 409 ROUND_HAS_RESERVATIONS

> 참가비를 바꿔도 이미 만들어진 예약의 결제 금액은 변하지 않는다. 활성 예약이 0건일 때만 수정되므로 충돌하지 않는다.
> 

---

#### deleteRound — DELETE /api/v1/expos/{expoId}/rounds/{roundId}

`ORGANIZER` 전용. 소프트 삭제. 활성 예약이 0건이고 아직 시작하지 않은 회차만.

**Response 204** No Content

**Errors** 403 FORBIDDEN, 404 NOT_FOUND, 409 ROUND_ALREADY_STARTED, 409 ROUND_HAS_RESERVATIONS, 503 DEPENDENCY_UNAVAILABLE(비공개 전환 실패 — 삭제도 롤백된다)

> **공개 박람회의 마지막 살아있는 회차를 삭제할 때는 로컬 삭제를 먼저 하고 비공개 전환을 같은 트랜잭션 끝에서 부른다.** 검증(예약 있음 등)에 걸리면 비공개 전환까지 가지 않고, 비공개 전환이 실패하면 삭제가 롤백된다. 어느 쪽이 실패해도 "회차 0개인 PUBLISHED" 나 "회차는 있는데 HIDDEN" 이 남지 않는다.
> 
> 
> **삭제해도 지난 예약 이력은 보존된다.** 삭제된 회차는 목록·새 예약·공개 조건·자동 마감에서 빠지고, 내 예약·정원 반환·발급된 티켓의 시각 확인에서는 빠지지 않는다.
> 

---

#### createReservation — POST /api/v1/rounds/{roundId}/reservations

`USER` 전용. 예약을 생성하고 정원을 차감한다. 결제는 이 호출에서 하지 않는다.

**Request**

```json
{ "headcount": 2, "contactName": "홍길동", "contactPhone": "010-1234-5678" }
```

| 필드 | 제약 |
| --- | --- |
| headcount | 1~7 |
| contactName | 필수, 최대 100자. 예약자와 계정주가 다를 수 있다(대리 예약) |
| contactPhone | 필수. `^01[0-9][- ]?\d{3,4}[- ]?\d{4}$`. 하이픈·공백은 허용하되 제거해 저장 |

**Response 201** + `Location: /api/v1/reservations/{reservationId}`

```json
{ "reservationId": 501, "reservationNo": "R-4K7Q-W2M8", "roundId": 68, "headcount": 2, "amount": 30000, "status": "PENDING", "expiresAt": "2026-09-08T09:10:00Z", "paymentId": "rsv-501-…" }
```

> 참가비가 0원인 회차는 즉시 `CONFIRMED` 로 생성되며 `expiresAt`·`paymentId` 가 응답에서 빠진다(null 필드는 직렬화하지 않는다).
> 
> 
> `paymentId` 는 PG 에 금액을 사전 등록한 식별자다. 프론트가 이 값으로 결제창을 연다.
> 

**Errors** 400 INVALID_REQUEST, 403 FORBIDDEN(역할), 404 NOT_FOUND(없는·삭제된·종료된 회차, 비공개 박람회), 409 CAPACITY_EXCEEDED, 409 DUPLICATE_RESERVATION, 409 RESERVATION_CLOSED(이미 시작), 503 DEPENDENCY_UNAVAILABLE(공개 여부 조회·PG 사전 등록 실패)

> **같은 회원의 같은 회차 유효 예약은 DB UNIQUE 로 막는다.** 유효(PENDING·CONFIRMED)일 때만 값이 생기는 생성 컬럼에 UNIQUE 를 걸었으므로 더블클릭도 두 번째가 409 다. 취소·만료 뒤 재예약은 된다.
> 
> 
> **예약은 회차 시작 시각 전까지만 받는다.** 환불은 시작 24시간 전까지 취소한 경우에만 되므로, 프론트는 24시간 이내로 남은 회차에 "환불되지 않는 회차" 경고를 띄운다.
> 

---

#### confirmReservation — POST /api/v1/reservations/{reservationId}/payment

`USER` 전용. 본인 예약만. 요청 본문 없음 — 서버가 저장된 `paymentId` 로 PG 에 직접 조회한다.

**Response 200**

```json
{ "reservationId": 501, "status": "CONFIRMED", "confirmedAt": "2026-09-08T09:03:00Z" }
```

| 현재 상태 | 처리 |
| --- | --- |
| PENDING | PG 조회 → 결제 완료·금액 일치면 CONFIRMED 전이, 커밋 후 티켓 발급 통지·추천 이벤트 |
| CONFIRMED | 재검증 없이 200 (멱등) |
| CANCELLED · EXPIRED | 409 INVALID_STATE_TRANSITION |

**Errors** 400 PAYMENT_AMOUNT_MISMATCH, 403 FORBIDDEN(타인의 예약 — 작업이므로 404 가 아니다), 404 NOT_FOUND, 409 INVALID_STATE_TRANSITION, 503 DEPENDENCY_UNAVAILABLE(PG 결과 모름 — 상태 유지)

> 결과를 반영하는 경로는 사용자 호출·PG 웹훅·만료 배치 셋이지만 같은 멱등 함수로 수렴한다.
> 

---

#### reservationWebhook — POST /api/v1/reservations/webhooks/portone

PortOne 이 부른다. 헤더·응답 규칙은 `promotionWebhook` 과 같다. 결과를 모르면 이벤트를 `RECEIVED` 로 남기고 503 을 돌려 재전송을 받는다.

---

#### listMyReservations — GET /api/v1/reservations/me

`USER` 전용. 본인 예약 최신순. 취소·만료 이력도 반환한다.

**Response 200**

```json
[
  {
    "reservationId": 501,
    "reservationNo": "R-4K7Q-W2M8",
    "expoId": 12,
    "expoTitle": "2026 글로벌 IT 박람회",
    "roundId": 68,
    "roundSequence": 1,
    "startsAt": "2026-09-10T01:00:00Z",
    "endsAt": "2026-09-10T09:00:00Z",
    "headcount": 2,
    "amount": 30000,
    "status": "CONFIRMED",
    "refundState": "NOT_APPLICABLE",
    "createdAt": "2026-09-08T09:00:00Z"
  }
]
```

| 필드 | 설명 |
| --- | --- |
| expoTitle | 표시용. 제목 일괄 조회에 실패하면 null 이고 화면이 `박람회 #12` 로 물러난다 |
| roundSequence · startsAt · endsAt | 표시용. 삭제된 회차를 가리키는 지난 예약은 `roundSequence` 가 null 이다 |
| refundState | NOT_APPLICABLE(무료·미결제) · REFUNDED · REFUND_PENDING(재시도 중) · REFUND_UNRESOLVED(재시도 상한 초과, 문의 필요) · NOT_REFUNDABLE(기한 경과) |

> 목록은 Ticket-Service 를 호출하지 않는다 — QR 은 상세에서만 필요하다.
> 

**Errors** 401 UNAUTHENTICATED, 403 FORBIDDEN(역할)

---

#### getMyReservation — GET /api/v1/reservations/{reservationId}

`USER` 전용. 본인 예약만. 연락처를 마스킹하지 않는다.

**Response 200** — 목록 항목에 아래가 추가된다

```json
{
  "contactName": "홍길동",
  "contactPhone": "01012345678",
  "ticketAvailable": true,
  "ticket": { "ticketId": 900, "checkinToken": "3f9a1c…(32 hex)", "issuedAt": "2026-09-08T09:03:00Z", "status": "ISSUED" }
}
```

**Errors** 404 NOT_FOUND(타인의 예약 — 403 이 아니다). 티켓 미발급·조회 실패 시 `ticketAvailable: false`, `ticket: null`

---

#### cancelReservation — PATCH /api/v1/reservations/{reservationId}/cancellation

`USER` 전용. 본인 예약만. `PENDING`·`CONFIRMED` 모두 취소 대상이다. 요청 본문 없음.

**Response 200**

```json
{ "reservationId": 501, "status": "CANCELLED", "refundState": "REFUNDED", "cancelledAt": "2026-09-08T10:00:00Z" }
```

**처리 순서** 취소 기한 확인 → CONFIRMED 면 티켓 입장 여부 확인(fail-closed) → PENDING 이면 PG 를 먼저 조회해 결제 상태만 맞춤 → 조건부 UPDATE 로 전이(0행이면 이미 끝난 예약, 그대로 200) → 정원 반환 → 환불 판정 → 티켓 무효화 통지

**Errors** 400 CANCELLATION_DEADLINE_PASSED(회차 시작 이후), 404 NOT_FOUND, 409 ALREADY_CHECKED_IN, 503 DEPENDENCY_UNAVAILABLE(티켓 상태 확인 실패)

> **취소 기한과 환불 기한이 다르다.** 취소는 회차 시작 전까지, 환불은 시작 24시간 전까지. 그 사이 취소는 `NOT_REFUNDABLE` 로 내려가고 화면이 "취소됨 · 환불 불가" 로 표시한다. 한 번도 확정되지 않은 PENDING(결제창 닫고 취소)은 기한과 무관하게 결제돼 있으면 환불한다.
> 
> 
> **환불 실패해도 예약 취소는 되돌리지 않는다.** 되돌리면 이미 다른 회원이 차지한 정원을 다시 빼야 한다. 환불만 배치로 재시도한다(`REFUND_PENDING` → 상한 초과 시 `REFUND_UNRESOLVED`).
> 

---

#### listCalendarEvents — GET /api/v1/calendar/events?from=&to=

인증 불필요. 기간 안에 있는 공개 박람회의 회차 목록(캘린더 표시용). 마감된 회차도 포함한다.

**Response 200**

```json
[
  { "roundId": 68, "expoId": 12, "expoTitle": "2026 글로벌 IT 박람회", "sequence": 1, "startsAt": "2026-09-10T01:00:00Z", "endsAt": "2026-09-10T09:00:00Z" }
]
```

**Errors** 400 INVALID_REQUEST(기간 90일 초과)

> 공개 박람회 id 와 제목은 expo-service 에 내부 조회한다. id 조회가 실패하면 빈 목록, 제목 조회가 실패하면 `expoTitle` 이 null 이다(부분 실패 허용). `Cache-Control: no-store`.
> 

---

#### suggestCalendar — GET /api/v1/me/calendar/suggest?from=&to=&constraint=

로그인한 누구나. AI 일정 추천(AI-2). 기간 안의 예약 가능한 회차 중 자연어 조건에 맞고 서로 겹치지 않는 일정을 고른다.

| 파라미터 | 제약 |
| --- | --- |
| from · to | 필수. 90일 이내 |
| constraint | 선택, 최대 200자. 예: "IT 분야, 오후만, 주말 제외" |

**Response 200**

```json
{
  "success": true,
  "data": [ { "roundId": 68, "expoId": 12, "expoTitle": "…", "sequence": 1, "startsAt": "…", "endsAt": "…" } ],
  "meta": { "constraintSource": "GEMINI", "candidateCount": 17 }
}
```

| meta 필드 | 설명 |
| --- | --- |
| constraintSource | GEMINI(LLM 해석) · RULE(LLM 실패, 정규식 규칙 해석) · NONE(조건 없음) |
| candidateCount | 조건 적용 전 후보 회차 수. 0이면 기간 안에 예약 가능한 회차가 없는 것이다 |

**Errors** 400 INVALID_REQUEST(기간·조건 길이), 401 UNAUTHENTICATED, 429 RATE_LIMITED(회원당 분당 5회)

> "LLM 은 필터를 만들고 목록은 DB 가 만든다." 후보는 DB 에서 가져오고 LLM 은 조건(카테고리·시간대·요일)만 해석한다. 카테고리 조건이 있는데 카테고리 조회에 실패하면 후보를 비운다(fail-closed) — 조건을 무시한 추천보다 빈 추천이 낫다. 규칙 해석은 "오후" 를 무시한다(알려진 제한).
> 

---

### Ticket-Service

체크인은 예약이 아니라 **티켓 단위**다. 조회(`verify`)로 티켓을 특정한 뒤 확정(`checkin`)한다. 세 엔드포인트 모두 **역할 검사를 티켓 조회보다 먼저** 하므로 회원 토큰으로는 404/403 차이로 토큰·예약번호 존재 여부를 알 수 없다.

#### verifyTicket — GET /api/v1/tickets/verify

`ORGANIZER` 전용. 상태를 바꾸지 않는다.

**Query Parameters**

| 파라미터 | 설명 |
| --- | --- |
| code | QR 의 체크인 토큰(32자리 hex) |
| reservationNo | 예약번호 `R-XXXX-XXXX`. QR 을 쓸 수 없을 때의 수동 입력 |

> 둘 중 **하나만** 보낸다. 둘 다 보내거나 하나도 안 보내면 400.
> 

**Response 200**

```json
{ "ticketId": 900, "status": "ISSUED", "reservationNo": "R-4K7Q-W2M8", "expoId": 12, "expoTitle": "2026 글로벌 IT 박람회", "roundId": 68, "roundSequence": 1, "headcount": 2, "issuedAt": "2026-09-08T09:03:00Z", "usedAt": null }
```

| 필드 | 설명 |
| --- | --- |
| expoTitle | 소유권 검증 응답에서 함께 받는다 |
| roundSequence | 회차 조회에 실패하면 null 이고 화면이 `round #68` 로 물러난다 |

**Errors** 400 INVALID_REQUEST(파라미터 개수), 401 UNAUTHENTICATED, 403 FORBIDDEN(역할·타인의 박람회 티켓), 404 NOT_FOUND(없는 토큰·예약번호 — 같은 메시지), 503 DEPENDENCY_UNAVAILABLE(소유권 조회 실패)

---

#### checkinTicket — POST /api/v1/tickets/{ticketId}/checkin?method=

`ORGANIZER` 전용. `ISSUED → USED`. 요청 본문 없음.

| 파라미터 | 설명 |
| --- | --- |
| method | 선택. `QR` · `RESERVATION_NO`. 이력에 기록되며 없으면 `UNKNOWN` |

**Response 200**

```json
{ "ticketId": 900, "status": "USED", "checkedInAt": "2026-09-10T00:55:00Z" }
```

**Errors** 403 FORBIDDEN, 404 NOT_FOUND, 409 CONFLICT(이미 USED·CANCELLED), 409 CHECKIN_NOT_OPEN(시작 1시간 전보다 이름), 409 CHECKIN_CLOSED(회차 종료), 503 DEPENDENCY_UNAVAILABLE(소유권 조회 실패)

> **체크인 시간창은 회차 시작 1시간 전 ~ 회차 종료 시각.** 시간창 판정은 fail-open — 회차 시각을 못 받으면 검증만 건너뛰고 WARN 로그. 소유권 조회 실패는 fail-closed.
> 
> 
> **티켓 행을 `SELECT … FOR UPDATE` 로 잠근 뒤 전이한다.** 같은 티켓을 두 단말이 동시에 찍어도 하나만 USED 가 되고 나머지는 409 다.
> 
> 이력 기록·추천 이벤트는 커밋 뒤에 보낸다(fail-open).
> 

---

#### cancelCheckin — POST /api/v1/tickets/{ticketId}/checkin/cancellation

`ORGANIZER` 전용. `USED → ISSUED`. 이미 `ISSUED` 면 상태를 바꾸지 않고 200(멱등).

**Response 200**

```json
{ "ticketId": 900, "status": "ISSUED", "checkedInAt": null }
```

**Errors** 403 FORBIDDEN, 404 NOT_FOUND, 409 CONFLICT(CANCELLED 티켓)

> 시간창을 보지 않는다 — 창이 닫힌 뒤에도 오처리는 복구돼야 한다. 422 는 쓰지 않는다.
> 

---

#### getCheckinReport — GET /api/v1/tickets/checkin-report?expoId=

`ORGANIZER` 전용. 체크인 결과 요약(AI-6). 본인 박람회만.

**Response 200**

```json
{
  "expoId": 12,
  "expoTitle": "2026 글로벌 IT 박람회",
  "reserved": 113,
  "capacity": 200,
  "checkedIn": 42,
  "noShow": 71,
  "checkinRate": 37,
  "rounds": [
    { "roundId": 31, "sequence": 1, "startsAt": "2026-10-01T01:00:00Z", "reserved": 60, "checkedIn": 30, "noShow": 30, "checkinRate": 50 },
    { "roundId": 32, "sequence": 2, "startsAt": "2026-10-02T01:00:00Z", "reserved": 53, "checkedIn": 12, "noShow": 41, "checkinRate": 23 }
  ],
  "hourly": [ { "date": "2026-10-01", "hour": 10, "count": 12 } ],
  "byMethod": { "QR": 30, "RESERVATION_NO": 10, "UNKNOWN": 2 },
  "reverted": 1,
  "summary": "예약 113명 중 42명이 입장했습니다. …"
}
```

| 필드 | 설명 |
| --- | --- |
| reserved · checkedIn · noShow | 박람회 전체(모든 회차 합산) 인원(headcount 합). 확정 예약 기준. `checkinRate` 는 0~100 정수 % |
| rounds | 회차별 예약·입장 인원. 필드 의미는 위 전체 값과 같다. 예약-Service 가 주는 회차 시작 순서이며 `sequence` 는 1부터. 예약 현황을 못 받아오면 입장 기록이 있는 회차만 roundId 순으로 오고, 이때 `sequence`·`startsAt` 은 null, `reserved`·`noShow`·`checkinRate` 는 0 |
| hourly · byMethod · reverted | 처리 **건수**(되돌린 것 포함). `hourly` 는 KST 날짜(`date`, yyyy-MM-dd)·시(`hour`, 0~23)별로 나눠 센다 — 여러 날에 걸친 회차의 같은 시간대가 합쳐지지 않는다. 입장이 없는 시간대는 빠진다. 인원 단위와 섞여 있다(알려진 제한) |
| summary | Gemini 요약. 키가 없거나 실패하면 null 이고 숫자는 정상이다(fail-open) |

**Errors** 403 FORBIDDEN(역할·타인의 박람회), 503 DEPENDENCY_UNAVAILABLE(소유권·예약 집계 조회 실패)

---

### Settlement-Service

#### getSettlement — GET /api/v1/admin/settlement?period=&date=&summary=

`SUPER_ADMIN` 전용. 기간별 정산 현황. **응답에 봉투가 없다**(raw).

**Query Parameters**

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| period | Enum | `DAY` · `WEEK` · `MONTH` · `YEAR`, 필수 |
| date | Date | `yyyy-MM-dd`, 필수. 이 날짜가 속한 기간을 집계한다 |
| summary | Boolean | 기본 false. true 면 Gemini 요약(`aiSummary`)을 붙인다 |
| platfromFee / feeRate | long / double | 순매출(예약 + VIP 배너)에 feeRate(0.10)을 곱한 값 |

**Response 200**

```json
{
  "period": "MONTH",
  "from": "2026-09-01",
  "to": "2026-09-30",
  "totalRevenue": 4500000,
  "totalRefund": 150000,
  "netRevenue": 4350000,
  "platformFee": 435000,
  "feeRate": 0.1,
  "reservationRevenue": 4400100,
  "reservationRefund": 150000,
  "promotionRevenue": 99000,
  "promotionRefund": 0,
  "reservationPaidCount": 213,
  "reservationRefundCount": 5,
  "promotionPaidCount": 10,
  "promotionRefundCount": 0,
  "buckets": [ { "label": "09-01", "revenue": 150000, "refund": 0, "net": 150000 } ],
  "topExpos": [ { "expoId": 12, "title": "2026 글로벌 IT 박람회", "revenue": 1200000 } ],
  "topCategories": [ { "category": "IT·전자", "revenue": 2100000 } ],
  "aiSummary": "9월 매출은 …"
}
```

> **집계는 사건 시각 기준이다.** 매출은 `paidAt`, 환불은 `cancelledAt` 이 기간 안에 있는 건을 센다(반열린 구간). 9월 결제를 10월에 환불하면 9월 매출은 그대로고 10월에 환불만 잡힌다. 결제 레코드는 Reservation·expo 에서 Pull 한다.
> 

**Errors** 400(파라미터 형식), 401 UNAUTHENTICATED, 403 FORBIDDEN, 503 DEPENDENCY_UNAVAILABLE(결제 조회 실패). 오류 본문은 `{ "success": false, "data": { "code" }, "message" }`

---

### recommendation-service

#### getInterests — GET /api/v1/me/interests

로그인한 누구나. 없으면 빈 배열.

**Response 200**

```json
{ "categories": ["IT·전자", "문화·예술"], "keywords": ["AI", "전시"] }
```

---

#### upsertInterests — PUT /api/v1/me/interests

로그인한 누구나. 기존 관심사를 전체 교체하고 취향 점수를 다시 매긴다.

**Request**

```json
{ "categories": ["IT·전자", "문화·예술"], "keywords": ["AI", "전시"] }
```

| 필드 | 제약 |
| --- | --- |
| categories | 최대 6개 |
| keywords | 최대 30개 |

**Response 200** — `getInterests` 와 동일

**Errors** 400 INVALID_REQUEST

---

#### getRecommendations — GET /api/v1/me/recommendations?size=

로그인한 누구나. 취향·행동 점수 기반 추천. `size` 기본 10, 최대 50.

**Response 200**

```json
{
  "recommendations": [ { "expoId": 12, "title": "2026 글로벌 IT 박람회", "matchedTags": ["AI", "IT·전자"], "score": 3.5 } ],
  "generatedAt": "2026-09-14T09:58:00"
}
```

> `generatedAt` 은 `LocalDateTime` 이라 `Z` 가 없다(서버 시각). 점수가 하나도 없으면 `{ "recommendations": [], "generatedAt": null }`. 점수는 관심사(카테고리 3.0 · 키워드 2.0) + 행동(조회 0.5 · 예약 확정 4.0 · 체크인 5.0, 30일 반감기)을 태그별로 합산하고, 박람회 태그와 겹치는 태그의 점수를 더한다. 공개 박람회만 후보이며, 이미 예약 확정한 박람회와 태그 없는 박람회는 빠진다.
> 

---

#### recordView — POST /api/v1/me/views/{expoId}

박람회 상세 조회 이벤트. 역할 무관, **항상 200** — 토큰이 없어도 200 `data: null` 이고 기록만 건너뛴다. 화면이 조용히(quiet) 부른다.

---

#### getNotifications — GET /api/v1/me/notifications

로그인한 누구나.

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| unreadOnly | Boolean | false | 읽지 않은 알림만 |
| page | Integer | **0** | 페이지 번호(0부터 — 다른 목록과 다르다) |
| size | Integer | 20 | 페이지 크기 |

**Response 200**

```json
{
  "notifications": [
    { "id": 1, "expoId": 13, "type": "RECOMMENDATION", "message": "관심 분야 IT·전자의 새 박람회 …", "isRead": false, "createdAt": "2026-09-13T14:00:00Z" }
  ],
  "hasNext": false
}
```

| type | 발생 시점 |
| --- | --- |
| RECOMMENDATION | 관심사와 맞는 박람회가 공개·태깅됐을 때. 문구는 Gemini 가 만들고 실패하면 고정 문구 |
| RESERVATION_CONFIRMED | 예약 확정 이벤트를 받았을 때 |

---

#### getUnreadCount — GET /api/v1/me/notifications/unread-count

**Response 200**

```json
{ "unreadCount": 3 }
```

---

#### readNotification — PATCH /api/v1/me/notifications/{id}/read

**Response 200** — `data: null`

**Errors** 404 NOT_FOUND(없는 알림), 403 FORBIDDEN(타인의 알림 — id 존재 여부가 드러난다, 알려진 제한)

---

#### readAllNotifications — PATCH /api/v1/me/notifications/read-all

내 알림 전부 읽음. **Response 200** — `data: null`

---

#### getSimilarExpos — GET /api/v1/expos/{expoId}/similar

인증 불필요. 태그가 겹치는 박람회 id. 태그가 없으면 빈 배열.

**Response 200**

```json
{ "expoIds": [5, 9, 21] }
```

> id 만 준다. 제목·썸네일은 프론트가 `listExpos` 결과나 상세 호출로 채운다.
> 

---

#### getExpoTags — GET /api/v1/expos/{expoId}/tags

인증 불필요.

**Response 200**

```json
{ "tags": ["AI", "IT·전자", "스타트업"] }
```

---

#### getBulkTags — GET /api/v1/expos/tags/bulk?ids=1&ids=2

인증 불필요. `{ "1": ["AI", …], "2": [...] }` 형태의 맵. 태그 없는 id 는 빠진다.

---

## 서비스 간 동기 계약

내부 API 는 성공 응답을 **봉투 없이 raw 로** 내보낸다(recommendation-service 만 봉투). 호출자가 응답을 그대로 역직렬화하므로 성공에 봉투를 씌우면 전 필드가 null 로 파싱되고 fail-open 경로면 그 실패가 조용히 삼켜진다. 모든 내부 호출은 `Authorization: Bearer {INTERNAL_TOKEN}` 과 `X-Trace-Id` 를 붙인다.

### 계약 1 — 예약 ↔ 티켓

#### issueTickets — Reservation → Ticket, POST /internal/v1/tickets

| 항목 | 값 |
| --- | --- |
| 목적 | 결제 확정 후 QR 티켓 발급 |
| 호출 시점 | 예약 확정 **커밋 뒤**. 즉시 시도가 실패하면 통지 큐에 남는다 |
| 재시도 | 1분 주기 배치. 1·2·4·8·16분 뒤, 6회째에 포기(약 31분). 포기 건은 `gave-up` 으로 본다 |
| 발급 직전 재확인 | 배치는 예약 행을 잠그고 아직 CONFIRMED 인지 다시 본다. 그 사이 취소됐으면 발급하지 않고 큐에서 뺀다 |
| 실패 처리 | fail-open. 예약은 확정 유지 |

**Request**

```json
{ "reservationId": 501, "reservationNo": "R-4K7Q-W2M8", "expoId": 12, "roundId": 68, "userId": 42, "headcount": 2 }
```

> `reservationNo` 를 함께 저장하는 이유는 QR 을 쓸 수 없을 때 예약번호로 체크인해야 하기 때문이다.
> 

**Response 201**

```json
{ "ticketId": 900, "checkinToken": "3f9a1c…(32 hex)", "issuedAt": "2026-09-08T09:03:00Z" }
```

> 같은 `reservationId` 로 다시 부르면 기존 티켓을 돌려준다(멱등).
> 

---

#### revokeTicket — Reservation → Ticket, PATCH /internal/v1/tickets/reservation/{reservationId}/revoke

| 항목 | 값 |
| --- | --- |
| 목적 | 예약 취소 시 QR 무효화 |
| 응답 | 204 No Content |
| 멱등성 | 발급 전이거나 이미 취소됐어도 성공한다. **이미 입장(USED)한 티켓은 조용히 건너뛴다** |
| 재시도 | 1·2·4·8·16·32·60·60·60·60·60분, 12회(약 6시간) |
| 실패 처리 | fail-open. 예약 취소는 되돌리지 않는다 |

> 무효화가 USED 를 건너뛰므로, 취소 경로는 무효화 통지 **전에** `findTicket` 으로 입장 여부를 따로 확인한다. 그 확인은 fail-closed 다.
> 

---

#### findTicket — Reservation → Ticket, GET /internal/v1/tickets/reservation/{reservationId}

내 예약 상세의 QR 과 취소 전 입장 여부 확인. 404 면 미발급.

**Response 200**

```json
{ "ticketId": 900, "checkinToken": "3f9a1c…", "issuedAt": "2026-09-08T09:03:00Z", "status": "ISSUED" }
```

---

#### checkinSummary — expo → Ticket, GET /internal/v1/tickets/checkin-summary?expoId=

주최자 예약 현황의 회차별 체크인 인원. 부분 실패 허용(`checkedIn: null`).

**Response 200**

```json
[ { "roundId": 68, "checkedIn": 42 } ]
```

---

#### gaveUpDispatches — 운영 조회, GET /internal/v1/ticket-dispatches/gave-up?type=&limit=

재시도 상한을 넘겨 포기한 통지 목록. `type` 은 `ISSUE` · `REVOKE`, `limit` 기본 100. 화면은 없고 운영자가 직접 부른다.

**Response 200**

```json
[ { "dispatchId": 7, "reservationId": 501, "reservationNo": "R-4K7Q-W2M8", "type": "ISSUE", "attempts": 6, "lastError": "…", "updatedAt": "…" } ]
```

---

### 계약 2 — expo ↔ 예약(회차)

#### getExpoInternal — Reservation·Ticket → expo, GET /internal/v1/expos/{expoId}

| 항목 | 값 |
| --- | --- |
| 목적 | 회차 등록·수정·삭제의 소유권 확인, 예약 신청의 공개 여부 확인, 체크인·결과 요약의 소유권 확인과 제목 표시 |
| 실패 처리 | fail-closed |

**Response 200** (없으면 404)

```json
{ "expoId": 12, "channelOwnerId": 43, "status": "PUBLISHED", "title": "2026 글로벌 IT 박람회" }
```

---

#### expoTitles — Reservation·Settlement → expo, GET /internal/v1/expos/titles?expoIds=

| 항목 | 값 |
| --- | --- |
| 목적 | 내 예약·캘린더·정산 상위 박람회에 제목 표시 |
| Request | Query `expoIds` 반복 파라미터. 1~200개, 벗어나면 빈 배열 |
| 실패 처리 | fail-open. 빈 목록을 돌려주고 화면은 제목 없이 표시한다 |

**Response 200**

```json
[ { "expoId": 12, "title": "2026 글로벌 IT 박람회" } ]
```

> 상태로 거르지 않는다 — 지난 예약의 박람회는 HIDDEN·CLOSED 일 수 있다.
> 

---

#### expoCategories — Reservation·Settlement → expo, GET /internal/v1/expos/categories?expoIds=

캘린더 추천의 카테고리 조건과 정산의 카테고리별 매출용. 규칙은 `expoTitles` 와 같다. 응답 `[ { "expoId": 12, "category": "IT·전자" } ]`.

> 캘린더 추천에서는 **fail-closed** 로 쓴다 — 조회 실패 시 카테고리 조건이 붙은 후보를 비운다.
> 

---

#### listPublishedExpos — Reservation·recommendation → expo, GET /internal/v1/expos

공개 박람회 전부. 캘린더 후보 범위와 추천 후보 범위. 실패 시 빈 목록(fail-open).

**Response 200**

```json
[ { "expoId": 12, "title": "…", "description": "…" } ]
```

---

#### roundsExists — expo → Reservation, GET /internal/v1/rounds/exists?expoId=

공개 전환 전 회차 존재 확인. 삭제된 회차는 세지 않는다. 응답 `{ "exists": true }`. 실패 시 공개를 거부하고 HIDDEN 유지(fail-closed).

---

#### listRoundsByExpo — expo → Reservation, GET /internal/v1/rounds?expoId=

박람회 상세의 회차 병합용. 삭제된 회차는 반환하지 않는다. 실패 시 부분 실패 허용(`roundsAvailable: false`).

**Response 200**

```json
[ { "roundId": 68, "expoId": 12, "sequence": 1, "startsAt": "…", "endsAt": "…", "capacity": 200, "remaining": 87, "fee": 15000 } ]
```

---

#### getRoundInternal — Ticket → Reservation, GET /internal/v1/rounds/{roundId}

체크인 시간창 판정과 회차 번호 표시용. **삭제된 회차도 반환한다** — 이미 발급된 티켓의 시각 확인용. 실패 시 fail-open(시간창 검증 생략).

---

#### roundsByDate — expo(검색) → Reservation, GET /internal/v1/rounds/by-date?from=&to=&expoIds=&bookableOnly=

| 항목 | 값 |
| --- | --- |
| 목적 | 자연어 검색의 기간 조건. 캘린더도 내부적으로 같은 조회를 쓴다 |
| Request | `from`·`to` Instant(90일 이내), `expoIds` 반복(호출자가 200개씩 나눠 보낸다), `bookableOnly` true 면 아직 시작하지 않은 회차만 |
| 실패 처리 | 검색에서는 **fail-closed**(503) — 날짜와 무관한 결과를 주지 않는다 |

**Response 200** — `listRoundsByExpo` 와 같은 형태

---

#### feeSummaries — expo → Reservation, GET /internal/v1/rounds/fee-summary?expoIds=

| 항목 | 값 |
| --- | --- |
| 목적 | 박람회 목록·검색의 유료·무료 배지 |
| Request | `expoIds` 반복, 최대 200개 |
| 실패 처리 | 부분 실패 허용. 목록은 200 으로 나가고 `paid: null` |

**Response 200**

```json
[ { "expoId": 12, "paid": true } ]
```

> 예약 가능한 회차가 하나도 없는 박람회는 응답에 담기지 않는다. 판정 근거가 없는 상태를 `false` 로 내리면 "무료" 라고 거짓말을 하게 된다.
> 

---

#### deadlineSort — expo → Reservation, POST /internal/v1/rounds/deadline-sort?page=&size=

| 항목 | 값 |
| --- | --- |
| 목적 | 박람회 목록의 모집마감일순 정렬과 페이지 슬라이싱 |
| Request | Body 에 후보 expoId 배열(JSON). URI 길이 제한을 피하려고 POST |
| 실패 처리 | fail-closed(503) |

**Response 200**

```json
{ "expoIds": [12, 5, 9], "totalElements": 42 }
```

> 예약 가능한 회차의 가장 가까운 종료 시각 순. 회차가 없는 박람회는 뒤로 간다.
> 

---

#### nearestDeadlines — expo → Reservation, GET /internal/v1/rounds/nearest-deadlines?expoIds=

박람회별 가장 가까운 회차 종료 시각. 응답 `[ { "expoId": 12, "nearestEndsAt": "…" } ]`. 목록 정렬이 `deadlineSort` 로 옮겨간 뒤 **호출하는 곳이 없다.** 지워도 되는 API 다.

---

#### finishedExpoIds — expo(스케줄러) → Reservation, GET /internal/v1/rounds/finished-expos?before=&afterExpoId=&limit=

모든 회차가 끝난 박람회 id 목록. 삭제된 회차는 판정에서 제외한다. `afterExpoId` 커서(기본 0)와 `limit`(기본 500, 최대 1000)으로 끊어 받는다.

**Response 200** — `[12, 15, 21]` (expoId 오름차순)

> expo-service 가 10분마다 커서를 옮겨 가며 호출해 `PUBLISHED → CLOSED` 로 자동 마감한다. 커서가 전진하지 않으면 루프를 멈춘다. 실패 시 이번 주기를 건너뛴다.
> 

---

#### unpublishExpoInternal — Reservation → expo, PATCH /internal/v1/expos/{expoId}/unpublish

마지막 살아있는 회차를 삭제하는 트랜잭션 **끝에서** 부른다. `PUBLISHED → HIDDEN`, 이미 HIDDEN·CLOSED 면 상태를 바꾸지 않고 현재 값을 돌려준다(멱등). 실패 시 회차 삭제가 롤백된다(fail-closed).

**Response 200** — `{ "expoId": 12, "status": "HIDDEN" }`

---

#### reservationSummary · attendees — expo·Ticket → Reservation

| 호출 | 경로 | 용도 |
| --- | --- | --- |
| expo · Ticket → Reservation | `GET /internal/v1/reservations/summary?expoId=` | 회차별 정원·확정·취소·시각. 예약 현황과 체크인 결과 요약 |
| expo → Reservation | `GET /internal/v1/reservations/attendees?expoId=&roundId=` | 예약자 명단(엑셀). `roundId` 선택 |

**summary Response 200**

```json
[ { "roundId": 68, "capacity": 200, "confirmed": 113, "cancelled": 7, "startsAt": "…", "endsAt": "…" } ]
```

**attendees Response 200**

```json
[ { "reservationId": 501, "reservationNo": "R-4K7Q-W2M8", "roundId": 68, "contactName": "홍길동", "contactPhone": "01012345678", "headcount": 2, "amount": 30000, "status": "CONFIRMED", "createdAt": "…" } ]
```

> 명단 응답은 개인정보를 담으므로 Log 에 본문을 남기지 않는다. DTO 의 `toString` 에서 이름·연락처를 제외한다.
> 

---

### 계약 3 — 정산 (Pull)

Settlement-Service 가 결제 레코드를 **Pull** 해 집계한다. 실시간 Push 는 없다. 결제 조회 실패는 503 `DEPENDENCY_UNAVAILABLE`(fail-closed), 제목·카테고리 조회 실패는 빈 제목·"기타" 카테고리로 물러난다(부분 실패 허용).

| 호출 | 경로 | 비고 |
| --- | --- | --- |
| Settlement → Reservation | `GET /internal/v1/reservations/payments?from=&to=` | `from`·`to` Instant. `paid_at` 또는 `cancelled_at` 이 구간에 걸리는 결제 전부 |
| Settlement → expo | `GET /internal/expo-promotions/payments?from=&to=` | 경로에 `/v1` 이 없다(코드 그대로 기록) |
| Settlement → expo | `GET /internal/v1/expos/titles`, `/categories` | 상위 박람회·카테고리 표시 |

**reservations/payments Response 200**

```json
[ { "paymentId": "rsv-501-…", "amount": 30000, "status": "PAID", "paidAt": "…", "cancelledAt": null, "updatedAt": "…", "reservationId": 501, "expoId": 12 } ]
```

**expo-promotions/payments Response 200**

```json
[ { "paymentId": "promo-3-…", "amount": 9900, "paidAt": "…", "cancelledAt": null, "status": "PAID", "expoId": 12 } ]
```

> 두 응답 모두 `paidAt`·`cancelledAt` 을 준다. Settlement 는 `status`·`updatedAt` 이 아니라 이 두 시각으로 매출·환불을 기간에 배정한다.
> 

---

### 계약 4 — recommendation-service 연동 (fail-open)

모든 호출은 **fail-open**. recommendation-service 가 죽거나 응답이 늦어도 호출자는 정상 처리를 계속한다. 응답은 봉투 `{ success: true, data: null }` 이지만 호출자는 본문을 읽지 않는다.

#### 4-a — 박람회 공개 — expo → recommendation, POST /internal/v1/recommendations/expo-published

`publishExpo` **커밋 뒤** 비동기. 태깅(`expo-tagging`)과 관심사 매칭 알림을 만든다.

**Request**

```json
{ "expoId": 12, "title": "2026 글로벌 IT 박람회", "description": "최신 IT 트렌드를 한눈에", "category": "IT·전자" }
```

**Response 200**

---

#### 4-b — 재태깅 — expo → recommendation, POST /internal/v1/recommendations/expos/{expoId}/retag

`updateExpo` 로 제목·소개문이 바뀌면 커밋 뒤 비동기로 부른다. 본문은 4-a 와 같다.

> 재태깅은 `category` 를 읽지 않는다. LLM 이 실패하면 분야와 무관하게 "기타" 태그가 된다(알려진 제한).
> 

---

#### 4-c — 행동 이벤트 — Reservation·Ticket → recommendation, POST /internal/v1/recommendations/events

예약 확정·체크인 **커밋 뒤** 비동기. 상세 조회는 프론트가 `recordView` 로 직접 보낸다.

**Request**

```json
{ "userId": 42, "expoId": 12, "eventType": "RESERVATION_CONFIRMED", "reservationId": 501, "reservationNo": "R-4K7Q-W2M8" }
```

| eventType | 발생 시점 | 점수 |
| --- | --- | --- |
| RESERVATION_CONFIRMED | 예약 확정 커밋 뒤. `RESERVATION_CONFIRMED` 알림도 만든다 | 4.0 |
| CHECKED_IN | 체크인 커밋 뒤 | 5.0 |
| PAGE_VIEWED | `recordView` (내부 호출 아님) | 0.5 |

**Response 200**

---

#### 4-d — 점수 재계산 — 운영, POST /internal/v1/recommendations/scores/recalculate

전 회원 점수 재계산. 이미 실행 중이면 409. 화면은 없다.

---

## 확정사항

1. JWT 는 Access Token 1시간 하나뿐이다. Refresh Token 은 없다
2. 역할은 `USER` · `ORGANIZER` · `SUPER_ADMIN` 세 가지다. 정확히 일치해야 하며 상위 역할이 하위 API 를 부를 수 없다
3. 회원가입은 항상 `USER` 다. 주최자는 신청 승인 또는 관리자 발급으로만 된다
4. 실명(`name`)과 닉네임(`nickname`)은 별개다. 닉네임만 유일하며 가입 때 자동 생성(`이름#1234`)되고 마이페이지에서 바꾼다
5. 소셜 로그인은 제공자 토큰이 우리 앱에 발급된 것인지(`aud`)와 이메일 검증 여부를 확인한다. 미검증 이메일은 자리표시 도메인으로 가입시키고, 그 도메인으로는 이메일 가입·로그인을 막는다
6. 정원 차감은 예약 생성 시 즉시, 취소·만료·결제 실패 시 즉시 반환한다. 반환은 조건부 UPDATE 로 정확히 1회만 일어난다
7. 같은 회원의 같은 회차 유효 예약은 DB UNIQUE(생성 컬럼)로 막는다. 취소·만료 후 재예약은 된다
8. 예약은 회차 시작 시각 전까지만 받는다. 진행 중인 회차는 예약할 수 없다
9. 취소는 회차 시작 전까지, 환불은 시작 24시간 전까지 취소한 경우에만 된다. 그 사이 취소는 "취소됨 · 환불 불가" 로 표시한다
10. 이미 현장 입장한 예약은 취소·환불할 수 없다. 입장 여부를 확인할 수 없을 때도 거절한다
11. QR 토큰은 티켓 발급 시 만드는 32자리 hex(UUID)다. JWT 가 아니며 예약번호 `R-XXXX-XXXX` 로도 체크인할 수 있다
12. 결제 확정 경로는 사용자 호출·PG 웹훅·만료 배치 셋이지만 같은 멱등 함수로 수렴한다. 웹훅은 Raw Body 서명 검증을 거치고, PG 결과를 모르면 처리 완료로 적지 않아 재전송을 받는다
13. 환불은 PG 원거래 취소만 지원한다. 실패 시 배치가 재시도하며 상한을 넘기면 `REFUND_UNRESOLVED`("환불 지연 — 문의 필요") 로 표시한다
14. 정산은 Settlement-Service 가 Pull 하고 사건 시각(`paidAt` · `cancelledAt`) 기준으로 집계한다. 지난 기간 매출은 소급 변경되지 않는다
15. `/internal/**` 은 Nginx 가 차단한다. 서비스 간 호출은 고정 내부 도메인과 공유 `INTERNAL_TOKEN` 을 쓴다
16. 체크인 시간창은 회차 시작 1시간 전 ~ 종료 시각. 회차 조회 실패 시 시간창 검증만 생략한다
17. 체크인 전이는 티켓 행 잠금 아래에서 일어난다. 동시 요청은 하나만 성공한다
18. 취소된 예약의 QR 은 즉시 `CANCELLED` 로 전환되어 체크인할 수 없다. 무효화 통지는 약 6시간까지 재시도한다
19. 티켓 발급 통지는 예약 확정 커밋 뒤에 보내고, 배치 재발급 직전에 예약이 아직 CONFIRMED 인지 잠금 아래에서 다시 본다
20. 체크인·되돌리기는 처리자·처리 시각·처리 방법(QR/RESERVATION_NO/UNKNOWN)을 기록한다. 되돌리기는 시간창을 보지 않고 이미 ISSUED 면 멱등 200 이다
21. 회차 수정·삭제는 활성 예약이 0건이고 아직 시작하지 않은 회차만 가능하다. 회차 수정은 네 필드 전체 교체다
22. 공개 박람회의 마지막 회차를 삭제하면 로컬 삭제 후 같은 트랜잭션 끝에서 비공개 전환을 부르고, 실패하면 삭제를 롤백한다
23. 모든 회차가 끝난 공개 박람회는 10분 주기 스케줄러가 커서로 끊어 받아 `CLOSED` 로 자동 마감한다
24. 회차 번호는 저장하지 않고 조회할 때마다 날짜순으로 다시 매긴다
25. 박람회 유료·무료는 예약 가능한 회차 중 참가비가 있는 회차가 하나라도 있는지로 판정한다. 판정할 회차가 없으면 표시하지 않는다
26. 박람회 제목·회차 번호는 전부 표시용이다. 조회에 실패하면 화면이 id 로 물러난다
27. 박람회 목록 sort: recommended · newest(둘 다 등록 최신순) · deadline(모집마감일순, Reservation 이 매김, 실패 시 503). VIP 배너는 별도 API 로 얹는다
28. VIP 배너는 9,900원 · 30일 · 슬롯 10. 신청은 `PUBLISHED` 박람회만, 노출도 `PUBLISHED` 만. 결제 확인은 서버가 PG 에 직접 조회하고 웹훅은 서명 검증을 거친다. 활성화 직전 슬롯을 다시 세어 초과면 환불한다
29. 배너 환불은 배너를 먼저 내리고 PG 환불은 실패해도 배치가 재시도한다
30. VIP 배너 결제와 예약 결제는 common-payment 모듈을 통해 같은 PortOne 테스트 채널(실제 API)을 쓴다. 로컬은 Mock
31. recommendation-service 연동은 모두 fail-open 이고 커밋 뒤에 보낸다. 장애 시 예약·결제·체크인에 영향 없다
32. AI 기능(소개글 초안 · 자연어 검색 · 일정 추천 · 알림 문구 · 태깅 · 체크인 요약 · 정산 요약)은 모두 Gemini 실패 시 규칙·템플릿·null 로 물러난다. 기능 이름: `expo-description`, `expo-search`, `calendar-constraint`, `notification-message`, `expo-tagging`, `checkin-report`, `settlement-summary`. 서비스별 일일 호출 상한(기본 1000)
33. "LLM 은 필터를 만들고 목록은 DB 가 만든다." 검색·추천 후보는 항상 DB 조회이며 LLM 은 조건만 해석한다. 기간·카테고리처럼 사용자가 명시한 조건은 조회 실패 시 fail-closed 다
34. 오류 코드는 항상 `data.code` 다. 401 에는 `WWW-Authenticate: Bearer` 를 붙인다
35. **PG 연동키(Secret)는 어떤 문서·저장소에도 평문으로 기록하지 않는다.** 환경변수와 gitignore 된 파일에만 둔다

## 알려진 제한

| # | 내용 | 위치 |
| --- | --- | --- |
| 1 | 배너 활성화 직전 슬롯 재확인이 잠금 없이 세어 마지막 한 자리를 두고 동시 확인하면 슬롯을 1 넘길 수 있다 | `ExpoPromotionService.activateOrRefund` |
| 2 | 배너 환불 응답에 환불 상태가 없다. 화면은 "환불 처리 중" 으로만 안내한다 | `refundPromotion` |
| 3 | 같은 채널명 동시 생성은 409 가 아니라 500 | expo `GlobalExceptionHandler` |
| 4 | AI 일정 추천의 규칙 해석이 "오후" 를 무시한다(Gemini 가 살아 있으면 영향 없음) | `RegexConstraintParser` |
| 5 | 재태깅 때 LLM 이 실패하면 분야와 무관하게 "기타" 태그 | `ExpoTagService.retag` |
| 6 | 타인 알림 읽음은 403 이라 id 존재 여부가 드러난다 | `NotificationService.markRead` |
| 7 | 체크인 결과 요약의 시간대별·방법별 집계는 건수, 입장 수는 인원 — 단위가 섞임 | `CheckinReportService` |
| 8 | 소셜로만 가입한 이메일로 이메일 로그인 시 BCrypt 비교 없이 즉시 401 — 응답 시간으로 계정 존재가 드러난다 | `AuthService.login` |
| 9 | recommended 와 newest 정렬이 같다 | `ExpoQueryService` |
| 10 | Settlement 오류 응답에 `meta` 가 없고 `ResponseStatusException` 외 오류는 Spring 기본 형식 | settlement `AdminSettlementController` |
| 11 | `X-Trace-Id` 가 settlement·recommendation 연동에서 전파되지 않는다 | 각 client |

## 변경 이력

| 버전 | 일자 | 내용 |
| --- | --- | --- |
| v.11 | 2026-09-18 | 스프린트 3 리뷰 반영. 역할·상태·봉투·경로 정리, `paid`·`sequence`·`expoTitle` 추가 |
| v.17 | 2026-09-28 | 개발 종료. dev(PR #331) 코드와 전수 대조. 오류 코드 위치(`data.code`)·Refresh 없음·소셜 로그인·마이페이지·배너 결제 확인/환불/웹훅·캘린더·AI 4종·체크인 요약·정산 파라미터 정정. 내부 계약 전체 재작성(by-date, deadline-sort, categories, finished-expos 커서, checkin-summary, payments, retag, gave-up). 확정사항 35개·알려진 제한 11개로 재정리 |
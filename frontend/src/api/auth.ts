import { api } from './client'
import type { ApiResponse } from '../types'

/**
 * identity-service 의 실제 LoginResponse.
 * userId / name / role 은 내려오지 않는다. Token 의 sub · role 클레임에서 꺼내 쓴다.
 */
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
}

export interface SignUpResponse {
  userId: number
  email: string
  name: string
  role: string
}

export interface JwtClaims {
  sub: string // userId (문자열)
  role: string // 'USER' | 'ORGANIZER' | 'SUPER_ADMIN'
  exp: number
}

/** Access Token 의 payload 를 디코드한다. 서명 검증은 서버가 한다(여기선 표시용). */
export function decodeJwt(token: string): JwtClaims {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const json = new TextDecoder().decode(
    Uint8Array.from(atob(payload), c => c.charCodeAt(0))
  )
  return JSON.parse(json) as JwtClaims
}

export const authApi = {
  // POST /api/v1/auth/signup
  signup: (data: { name: string; email: string; password: string }) =>
    api.post<ApiResponse<SignUpResponse>>('/auth/signup', data),

  // POST /api/v1/auth/login
  login: (data: { email: string; password: string }) =>
    api.post<ApiResponse<LoginResponse>>('/auth/login', data),

  // POST /api/v1/auth/google — 구글 액세스 토큰으로 로그인(신규는 자동 가입)
  google: (data: { googleAccessToken: string }) =>
    api.post<ApiResponse<LoginResponse>>('/auth/google', data),

  // POST /api/v1/auth/naver — 네이버 액세스 토큰으로 로그인(신규는 자동 가입)
  naver: (data: { naverAccessToken: string }) =>
    api.post<ApiResponse<LoginResponse>>('/auth/naver', data),

  // POST /api/v1/auth/kakao — 카카오 인가 코드로 로그인(백엔드가 토큰 교환, 신규는 자동 가입)
  kakao: (data: { code: string; redirectUri: string }) =>
    api.post<ApiResponse<LoginResponse>>('/auth/kakao', data),

  // POST /api/v1/admin/organizers  (SUPER_ADMIN 전용)
  createOrganizerAccount: (data: {
    name: string
    email: string
    password: string
  }) => api.post<ApiResponse<SignUpResponse>>('/admin/organizers', data),
}

// 로그아웃 API 는 없다. Access Token 은 서버에 상태가 없으므로
// 클라이언트에서 Token 을 버리는 것으로 끝난다(AuthContext.logout).
// 주최자 신청(host-requests) 은 Sprint 2 범위라 아직 엔드포인트가 없다.

import { api } from './client'
import type { ApiResponse } from '../types'

export interface MyProfileResponse {
  id: number
  email: string
  // 실명. 가입 때 입력한 값이고 겹칠 수 있다.
  name: string
  // 화면에 보이는 이름. 유일하다.
  nickname: string
  role: string
  profileImageUrl: string | null
  // 소셜 가입 회원은 false - 비밀번호 변경 카드를 숨긴다
  hasPassword: boolean
}

export interface NicknameAvailabilityResponse {
  available: boolean
}

export const userApi = {
  // GET /api/v1/users/me
  getMe: () => api.get<ApiResponse<MyProfileResponse>>('/users/me'),
  // GET /api/v1/users/me/nickname-availability?nickname=
  checkNicknameAvailability: (nickname: string) =>
    api.get<ApiResponse<NicknameAvailabilityResponse>>(
      `/users/me/nickname-availability?nickname=${encodeURIComponent(nickname)}`),
  // PATCH /api/v1/users/me/nickname
  changeNickname: (nickname: string) =>
    api.patch<ApiResponse<MyProfileResponse>>('/users/me/nickname', { nickname }),
  // PATCH /api/v1/users/me/profile-image
  changeProfileImage: (imageUrl: string) =>
    api.patch<ApiResponse<MyProfileResponse>>('/users/me/profile-image', { imageUrl }),
  // PATCH /api/v1/users/me/password
  changePassword: (currentPassword: string, newPassword: string) =>
    api.patch<ApiResponse<null>>('/users/me/password', { currentPassword, newPassword }),
}

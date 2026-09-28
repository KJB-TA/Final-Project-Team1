import { api } from './client'
import type { ApiResponse } from '../types'

export interface MyProfileResponse {
  id: number
  email: string
  name: string
  role: string
  profileImageUrl: string | null
  // 소셜 가입 회원은 false - 비밀번호 변경 카드를 숨긴다
  hasPassword: boolean
}

export interface NameAvailabilityResponse {
  available: boolean
}

export const userApi = {
  // GET /api/v1/users/me
  getMe: () => api.get<ApiResponse<MyProfileResponse>>('/users/me'),
  // GET /api/v1/users/me/name-availability?name=
  checkNameAvailability: (name: string) =>
    api.get<ApiResponse<NameAvailabilityResponse>>(`/users/me/name-availability?name=${encodeURIComponent(name)}`),
  // PATCH /api/v1/users/me/name
  changeName: (name: string) => api.patch<ApiResponse<MyProfileResponse>>('/users/me/name', { name }),
  // PATCH /api/v1/users/me/profile-image
  changeProfileImage: (imageUrl: string) =>
    api.patch<ApiResponse<MyProfileResponse>>('/users/me/profile-image', { imageUrl }),
  // PATCH /api/v1/users/me/password
  changePassword: (currentPassword: string, newPassword: string) =>
    api.patch<ApiResponse<null>>('/users/me/password', { currentPassword, newPassword }),
}

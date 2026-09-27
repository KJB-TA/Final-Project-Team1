import { api } from './client'
import type { ActivePromotion, ApiResponse, Expo, Channel, ReservationSummary } from '../types'

const API_BASE = '/api/v1'
function getToken() {
  return localStorage.getItem('token')
}

export interface PublicationResponse {
  expoId: number
  status: 'HIDDEN' | 'PUBLISHED' | 'CLOSED'
}

export type ExpoSort = 'recommended' | 'newest' | 'deadline'

/**
 * 소개글 초안 응답. applied=false 면 초안을 만들지 못했다는 뜻이고 description 은 null 이다.
 * 이때도 200 이라, 화면은 안내만 띄우고 입력창을 그대로 둔다.
 */
export interface DescriptionDraft {
  description: string | null
  applied: boolean
}

/** 시스템이 문장을 어떻게 읽었는지. 값이 null 이면 그 조건을 못 뽑았다는 뜻이다. */
export interface SearchInterpretation {
  region: string | null
  category: string | null
  paid: boolean | null
  dateFrom: string | null
  dateTo: string | null
  keyword: string | null
}

/** 해석 칩 하나를 가리키는 키. 방문자가 지울 수 있는 단위다. */
export type SearchCondition = 'region' | 'category' | 'paid' | 'date' | 'keyword'

export interface ExpoSearchResult {
  interpreted: SearchInterpretation
  aiApplied: boolean
  expos: Expo[]
}

export interface ApplyPromotionResponse {
  promotionId: number
  expoId: number
  amount: number
  paymentId: string
  status: string
}

export const expoApi = {
  /**
   * GET /api/v1/expos — PUBLISHED 만 내려온다. 인증 불필요.
   * 백엔드가 받는 파라미터는 region · category · keyword · sort · page(1부터) · size(최대 100).
   * keyword는 제목·소개문·장소에 포함되는지로 필터한다.
   */
  listPublished: (params?: {
    category?: string
    region?: string
    keyword?: string
    sort?: ExpoSort
    page?: number
    size?: number
  }) => {
    const q = new URLSearchParams()
    if (params?.category) q.set('category', params.category)
    if (params?.region) q.set('region', params.region)
    if (params?.keyword) q.set('keyword', params.keyword)
    if (params?.sort) q.set('sort', params.sort)
    q.set('page', String(params?.page ?? 1))
    q.set('size', String(params?.size ?? 12))
    return api.get<ApiResponse<Expo[]>>(`/expos?${q}`)
  },

  /**
   * GET /api/v1/expos/search — 자연어 검색. 인증 불필요.
   * aiApplied=false 는 문장을 해석하지 못해 입력을 통째로 키워드로 찾았다는 뜻이다(검색은 그대로 된다).
   * ignore 는 방문자가 지운 해석 칩이다 - 조건을 빼면 결과가 넓어져야 해서 서버가 다시 찾는다.
   * 날짜 조건이 있는데 회차 조회가 실패하면 503 이다. 날짜를 무시한 목록을 주지 않는다.
   */
  searchExpos: (q: string, ignore: SearchCondition[] = [], params?: { page?: number; size?: number }) => {
    const p = new URLSearchParams()
    p.set('q', q)
    ignore.forEach(c => p.append('ignore', c))
    p.set('page', String(params?.page ?? 1))
    p.set('size', String(params?.size ?? 100))
    return api.get<ApiResponse<ExpoSearchResult>>(`/expos/search?${p}`)
  },

  /**
   * GET /api/v1/expo-promotions/active — 인증 불필요.
   * recommended 탭 상단 VIP 배너용. 백엔드가 정렬에 섞어주지 않으므로 프론트가 별도로 불러 조합한다.
   */
  getActivePromotions: () => api.get<ApiResponse<ActivePromotion[]>>('/expo-promotions/active'),

  /**
   * GET /api/v1/expos/{expoId} — PUBLISHED 가 아니면 404 다.
   * 응답에 rounds · roundsAvailable 이 함께 들어온다
   * (expo-service 가 reservation-service 의 내부 API 를 호출해 병합한 결과).
   */
  getExpo: (expoId: number) => api.get<ApiResponse<Expo>>(`/expos/${expoId}`),

  // POST /api/v1/channels
  createChannel: (data: { name: string; description?: string }) =>
    api.post<ApiResponse<Channel>>('/channels', data),

  // GET /api/v1/channels/my — 채널이 없으면 404
  getMyChannel: () => api.get<ApiResponse<Channel>>('/channels/my'),

  /**
   * GET /api/v1/channels/{channelId}/expos — 주최자용 목록. HIDDEN·CLOSED 도 내려온다.
   * 공개 목록(listPublished)을 channelId 로 거르면 비공개 박람회가 통째로 빠진다.
   */
  listMyExpos: (channelId: number) =>
    api.get<ApiResponse<Expo[]>>(`/channels/${channelId}/expos`),

  /** GET /api/v1/channels/{channelId}/expos/{expoId} — 본인 채널이면 HIDDEN 도 200, 남의 것은 404. */
  getMyExpo: (channelId: number, expoId: number) =>
    api.get<ApiResponse<Expo>>(`/channels/${channelId}/expos/${expoId}`),

  /** channelId 를 모르는 화면용. 내 채널을 먼저 찾아 비공개 박람회까지 읽는다. */
  getMyExpoById: async (expoId: number) => {
    const ch = await expoApi.getMyChannel()
    return expoApi.getMyExpo(ch.data.id, expoId)
  },

  /** PATCH /api/v1/channels/{channelId}/expos/{expoId} — 보내지 않은 필드는 그대로 둔다. */
  updateExpo: (
    channelId: number,
    expoId: number,
    data: {
      title?: string
      description?: string
      category?: string
      region?: string
      venue?: string
      thumbnailUrl?: string
      detailImageUrls?: string[]
    }
  ) => api.patch<ApiResponse<Expo>>(`/channels/${channelId}/expos/${expoId}`, data),

  // POST /api/v1/channels/{channelId}/expos — 생성 직후 상태는 HIDDEN
  createExpo: (
    channelId: number,
    data: {
      title: string
      description?: string
      category: string
      region?: string
      venue?: string
      thumbnailUrl?: string
      detailImageUrls?: string[]
    }
  ) => api.post<ApiResponse<Expo>>(`/channels/${channelId}/expos`, data),

  /**
   * POST /api/v1/channels/{channelId}/expos/description-draft — 주최자 본인 채널만.
   * 키워드와 이미 입력한 값을 넘겨 소개글 초안을 받는다. 저장하지 않는다 - 응답만 돌려주고
   * 주최자가 화면에서 고친 뒤 저장 버튼을 눌러야 DB 에 들어간다.
   */
  draftDescription: (
    channelId: number,
    data: {
      keywords: string[]
      title?: string
      category?: string
      venue?: string
      region?: string
    }
  ) => api.post<ApiResponse<DescriptionDraft>>(`/channels/${channelId}/expos/description-draft`, data),

  /**
   * POST /api/v1/expos/{expoId}/publication — PATCH /publish 아님.
   * 회차가 하나도 없으면 400, 이미 공개면 멱등 200, 종료된 박람회는 409.
   * reservation-service 가 죽어 있으면 503 이고 상태는 그대로 유지된다.
   */
  publishExpo: (expoId: number) =>
    api.post<ApiResponse<PublicationResponse>>(`/expos/${expoId}/publication`, {}),

  // POST /api/v1/expo-promotions — paymentId 발급 (결제 전 단계)
  applyPromotion: (expoId: number) =>
    api.post<ApiResponse<ApplyPromotionResponse>>('/expo-promotions', { expoId }),

  // POST /api/v1/expo-promotions/{promotionId}/payment — 결제창이 닫힌 뒤 서버가 PG 에 직접 확인한다
  confirmPromotionPayment: (promotionId: number) =>
    api.post<ApiResponse<{ promotionId: number; status: string }>>(`/expo-promotions/${promotionId}/payment`, {}),

  // POST /api/v1/expo-promotions/{promotionId}/refund
  refundPromotion: (promotionId: number) =>
    api.post<ApiResponse<void>>(`/expo-promotions/${promotionId}/refund`, {}),

  // GET /api/v1/expos/{expoId}/reservations/summary — 주최자(채널 소유자) 전용.
  getReservationSummary: (expoId: number) =>
    api.get<ApiResponse<ReservationSummary>>(`/expos/${expoId}/reservations/summary`),

  /**
   * GET /api/v1/expos/{expoId}/reservations/attendees.xlsx — 주최자 전용, 바이너리 응답.
   * apiFetch(JSON 파싱 전제)를 쓸 수 없어 fetch 를 직접 호출해 blob 으로 받고 다운로드를 트리거한다.
   * roundId 를 생략하면 박람회 전체 명단이 내려온다.
   */
  downloadAttendeesExcel: async (expoId: number, roundId?: number) => {
    const q = roundId ? `?roundId=${roundId}` : ''
    const token = getToken()
    const res = await fetch(`${API_BASE}/expos/${expoId}/reservations/attendees.xlsx${q}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) {
      const body = await res.json().catch(() => null)
      throw Object.assign(new Error('download failed'), { status: res.status, body })
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'attendees.xlsx'
    a.click()
    URL.revokeObjectURL(url)
  },
}



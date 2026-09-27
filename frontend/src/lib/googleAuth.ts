// 구글 로그인(Google Identity Services) 토큰 흐름 헬퍼.
// 버튼을 누를 때 GIS 스크립트를 지연 로드하고, 팝업으로 액세스 토큰을 받아 반환한다.
// 이 토큰을 백엔드(POST /api/v1/auth/google)로 넘기면 서버가 신원을 확인한다.

declare global {
  interface Window {
    google?: {
      accounts: {
        oauth2: {
          initTokenClient(config: {
            client_id: string
            scope: string
            prompt?: string
            callback: (response: { access_token?: string; error?: string }) => void
          }): { requestAccessToken: () => void }
        }
      }
    }
  }
}

const GSI_SRC = 'https://accounts.google.com/gsi/client'

// Vite 빌드 시점에 주입되는 공개 값. 없으면 구글 로그인 버튼을 숨긴다.
export const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined
export const googleLoginEnabled = !!GOOGLE_CLIENT_ID

let gsiPromise: Promise<void> | null = null

function loadGsi(): Promise<void> {
  if (window.google?.accounts?.oauth2) return Promise.resolve()
  if (gsiPromise) return gsiPromise
  gsiPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = GSI_SRC
    script.async = true
    script.defer = true
    script.onload = () => resolve()
    script.onerror = () => {
      gsiPromise = null
      reject(new Error('구글 로그인 스크립트를 불러오지 못했습니다.'))
    }
    document.head.appendChild(script)
  })
  return gsiPromise
}

/** 구글 로그인 팝업을 띄워 액세스 토큰을 받는다. 사용자가 닫거나 실패하면 reject. */
export async function requestGoogleAccessToken(): Promise<string> {
  if (!GOOGLE_CLIENT_ID) throw new Error('구글 로그인이 설정되지 않았습니다.')
  await loadGsi()
  return new Promise<string>((resolve, reject) => {
    const client = window.google!.accounts.oauth2.initTokenClient({
      client_id: GOOGLE_CLIENT_ID!,
      scope: 'openid email profile',
      // 매번 계정 선택을 띄운다(자동으로 이전 계정에 붙지 않게).
      prompt: 'select_account',
      callback: (response) => {
        if (response.error || !response.access_token) {
          reject(new Error(response.error || '구글 인증이 취소되었습니다.'))
          return
        }
        resolve(response.access_token)
      },
    })
    client.requestAccessToken()
  })
}

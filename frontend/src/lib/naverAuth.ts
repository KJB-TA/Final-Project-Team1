// 네이버 로그인(naveridlogin JS SDK) 팝업 흐름 헬퍼.
// 버튼을 누르면 팝업으로 네이버 로그인을 띄우고, 콜백 페이지(/auth/naver/callback)가
// 액세스 토큰을 postMessage 로 돌려준다. 이 토큰을 백엔드(POST /api/v1/auth/naver)로 넘긴다.

interface NaverLoginInstance {
  init: () => void
  getLoginStatus: (cb: (status: boolean) => void) => void
  accessToken?: { accessToken: string }
}

interface NaverLoginOptions {
  clientId: string
  callbackUrl: string
  isPopup?: boolean
  callbackHandle?: boolean
  loginButton?: { color: string; type: number; height: number }
}

interface NaverNamespace {
  LoginWithNaverId: new (options: NaverLoginOptions) => NaverLoginInstance
}

declare global {
  interface Window {
    naver?: NaverNamespace
  }
}

const NAVER_SDK_SRC = 'https://static.nid.naver.com/js/naveridlogin_js_sdk_2.0.2.js'

export const NAVER_CLIENT_ID = import.meta.env.VITE_NAVER_CLIENT_ID as string | undefined
export const naverLoginEnabled = !!NAVER_CLIENT_ID

let sdkPromise: Promise<void> | null = null

export function loadNaverSdk(): Promise<void> {
  if (window.naver?.LoginWithNaverId) return Promise.resolve()
  if (sdkPromise) return sdkPromise
  sdkPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = NAVER_SDK_SRC
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      sdkPromise = null
      reject(new Error('네이버 로그인 스크립트를 불러오지 못했습니다.'))
    }
    document.head.appendChild(script)
  })
  return sdkPromise
}

export function naverCallbackUrl(): string {
  return window.location.origin + '/auth/naver/callback'
}

/** 네이버 로그인 팝업을 띄워 액세스 토큰을 받는다. 콜백 페이지가 postMessage 로 토큰을 돌려준다. */
export async function requestNaverAccessToken(): Promise<string> {
  if (!NAVER_CLIENT_ID) throw new Error('네이버 로그인이 설정되지 않았습니다.')
  await loadNaverSdk()

  // SDK 가 로그인 앵커를 심을 숨김 컨테이너
  let container = document.getElementById('naverIdLogin')
  if (!container) {
    container = document.createElement('div')
    container.id = 'naverIdLogin'
    container.style.display = 'none'
    document.body.appendChild(container)
  }

  const naverLogin = new window.naver!.LoginWithNaverId({
    clientId: NAVER_CLIENT_ID,
    callbackUrl: naverCallbackUrl(),
    isPopup: true,
    loginButton: { color: 'green', type: 3, height: 40 },
  })
  naverLogin.init()

  return new Promise<string>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      cleanup()
      reject(new Error('네이버 인증이 취소되었습니다.'))
    }, 120000)

    function onMessage(e: MessageEvent) {
      if (e.origin !== window.location.origin) return
      const data = e.data as { type?: string; accessToken?: string; error?: string }
      if (data?.type !== 'NAVER_AUTH') return
      cleanup()
      if (data.accessToken) resolve(data.accessToken)
      else reject(new Error(data.error || '네이버 인증에 실패했습니다.'))
    }

    function cleanup() {
      window.clearTimeout(timer)
      window.removeEventListener('message', onMessage)
    }

    window.addEventListener('message', onMessage)

    // SDK 가 컨테이너에 만든 앵커를 눌러 팝업을 연다.
    const anchor = container!.querySelector('a')
    if (anchor) anchor.click()
    else {
      cleanup()
      reject(new Error('네이버 로그인 버튼을 만들지 못했습니다.'))
    }
  })
}

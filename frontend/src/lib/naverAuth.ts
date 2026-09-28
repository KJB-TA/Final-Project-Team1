// 네이버 로그인(인가 코드 방식). SDK 토큰 방식은 기존 세션을 그냥 재사용해 매번 계정 확인을
// 강제할 수 없어, 카카오와 동일하게 인가 URL 팝업 + code 방식으로 바꿨다.
// auth_type=reprompt 로 매번 동의/계정 확인 화면을 띄운다. code 는 백엔드가 토큰으로 교환한다.

export const NAVER_CLIENT_ID = import.meta.env.VITE_NAVER_CLIENT_ID as string | undefined
export const naverLoginEnabled = !!NAVER_CLIENT_ID

export interface NaverAuthResult {
  code: string
  state: string
}

export function naverCallbackUrl(): string {
  return window.location.origin + '/auth/naver/callback'
}

/** 네이버 인가 팝업을 띄워 code 를 받는다. 콜백 페이지가 code·state 를 postMessage 로 돌려준다. */
export async function requestNaverAuthCode(): Promise<NaverAuthResult> {
  if (!NAVER_CLIENT_ID) throw new Error('네이버 로그인이 설정되지 않았습니다.')
  const redirectUri = naverCallbackUrl()
  // CSRF 방지용 state. 콜백에서 그대로 돌아오면 우리가 시작한 요청임을 확인한다.
  // 공격자가 값을 예측하지 못하도록 Math.random 이 아닌 암호학적 난수를 쓴다.
  const state = crypto.randomUUID()
  const url =
    'https://nid.naver.com/oauth2.0/authorize' +
    '?response_type=code' +
    '&client_id=' + encodeURIComponent(NAVER_CLIENT_ID) +
    '&redirect_uri=' + encodeURIComponent(redirectUri) +
    '&state=' + encodeURIComponent(state) +
    '&auth_type=reprompt'

  const popup = window.open(url, 'naver_login', 'width=480,height=700')
  if (!popup) throw new Error('팝업이 차단되었습니다. 팝업 허용 후 다시 시도해주세요.')

  return new Promise<NaverAuthResult>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      cleanup()
      reject(new Error('네이버 인증이 취소되었습니다.'))
    }, 120000)

    // 사용자가 팝업을 X 로 닫으면 message 가 오지 않아 120초를 다 기다리게 된다. 닫힘을 감지해 바로 취소한다.
    // 콜백 페이지가 code 를 보내고 스스로 닫는 경우 message 가 조금 늦게 올 수 있어, 잠깐 기다린 뒤 판단한다.
    let closedCheck: number | undefined
    const closedPoll = window.setInterval(() => {
      if (!popup.closed) return
      window.clearInterval(closedPoll)
      closedCheck = window.setTimeout(() => {
        cleanup()
        reject(new Error('네이버 인증이 취소되었습니다.'))
      }, 500)
    }, 500)

    function onMessage(e: MessageEvent) {
      if (e.origin !== window.location.origin) return
      const data = e.data as { type?: string; code?: string; state?: string; error?: string }
      if (data?.type !== 'NAVER_AUTH') return
      cleanup()
      if (data.error) reject(new Error(data.error))
      else if (data.state !== state) reject(new Error('네이버 인증 상태값이 일치하지 않습니다.'))
      else if (data.code) resolve({ code: data.code, state: data.state })
      else reject(new Error('네이버 인가 코드를 받지 못했습니다.'))
    }

    function cleanup() {
      window.clearTimeout(timer)
      window.clearInterval(closedPoll)
      window.clearTimeout(closedCheck)
      window.removeEventListener('message', onMessage)
      try {
        popup?.close()
      } catch {
        /* 팝업이 이미 닫혔으면 무시 */
      }
    }

    window.addEventListener('message', onMessage)
  })
}

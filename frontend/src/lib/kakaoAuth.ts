// 카카오 로그인(인가 코드 방식). JS SDK v2 에는 팝업 토큰 로그인이 없어,
// 팝업으로 카카오 인가 페이지를 열어 code 를 받고, 그 code 를 백엔드로 넘겨 토큰 교환·조회를 맡긴다.
// 콜백 페이지(/auth/kakao/callback)가 code 를 postMessage 로 돌려준다.

// 인가 URL 의 client_id 로 쓰는 REST API 키(공개 값). 없으면 카카오 로그인 버튼이 숨는다.
export const KAKAO_REST_KEY = import.meta.env.VITE_KAKAO_REST_KEY as string | undefined
export const kakaoLoginEnabled = !!KAKAO_REST_KEY

export interface KakaoAuthResult {
  code: string
  redirectUri: string
}

export function kakaoRedirectUri(): string {
  return window.location.origin + '/auth/kakao/callback'
}

/** 카카오 인가 팝업을 띄워 code 를 받는다. 콜백 페이지가 postMessage 로 code 를 돌려준다. */
export async function requestKakaoAuthCode(): Promise<KakaoAuthResult> {
  if (!KAKAO_REST_KEY) throw new Error('카카오 로그인이 설정되지 않았습니다.')
  const redirectUri = kakaoRedirectUri()
  // 방안 B: 이메일은 비즈앱이라야 받을 수 있어 닉네임만 요청한다. 서버가 이메일 없으면 placeholder 로 처리.
  const url =
    'https://kauth.kakao.com/oauth/authorize' +
    '?response_type=code' +
    '&client_id=' + encodeURIComponent(KAKAO_REST_KEY) +
    '&redirect_uri=' + encodeURIComponent(redirectUri) +
    '&scope=' + encodeURIComponent('profile_nickname')

  const popup = window.open(url, 'kakao_login', 'width=480,height=640')
  if (!popup) throw new Error('팝업이 차단되었습니다. 팝업 허용 후 다시 시도해주세요.')

  return new Promise<KakaoAuthResult>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      cleanup()
      reject(new Error('카카오 인증이 취소되었습니다.'))
    }, 120000)

    function onMessage(e: MessageEvent) {
      if (e.origin !== window.location.origin) return
      const data = e.data as { type?: string; code?: string; error?: string }
      if (data?.type !== 'KAKAO_AUTH') return
      cleanup()
      if (data.code) resolve({ code: data.code, redirectUri })
      else reject(new Error(data.error || '카카오 인증에 실패했습니다.'))
    }

    function cleanup() {
      window.clearTimeout(timer)
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

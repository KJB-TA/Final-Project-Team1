import { useEffect } from 'react'
import { loadNaverSdk, NAVER_CLIENT_ID, naverCallbackUrl } from '../lib/naverAuth'

/**
 * 네이버 로그인 팝업이 인증 후 되돌아오는 페이지.
 * SDK 가 URL 해시에서 액세스 토큰을 파싱하면, 그 토큰을 여는 창(opener)으로 보내고 팝업을 닫는다.
 */
export default function NaverCallback() {
  useEffect(() => {
    let settled = false

    function post(message: { type: 'NAVER_AUTH'; accessToken?: string; error?: string }) {
      if (window.opener) window.opener.postMessage(message, window.location.origin)
      window.close()
    }

    async function run() {
      try {
        await loadNaverSdk()
        const naverLogin = new window.naver!.LoginWithNaverId({
          clientId: NAVER_CLIENT_ID!,
          callbackUrl: naverCallbackUrl(),
          isPopup: true,
          callbackHandle: true,
        })
        naverLogin.init()
        naverLogin.getLoginStatus((status) => {
          if (settled) return
          settled = true
          const token = naverLogin.accessToken?.accessToken
          if (status && token) post({ type: 'NAVER_AUTH', accessToken: token })
          else post({ type: 'NAVER_AUTH', error: '토큰을 받지 못했습니다.' })
        })
      } catch {
        if (!settled) {
          settled = true
          post({ type: 'NAVER_AUTH', error: '네이버 로그인 처리에 실패했습니다.' })
        }
      }
    }

    run()
  }, [])

  return <div style={{ padding: 24, textAlign: 'center' }}>네이버 로그인 처리 중입니다…</div>
}

import { useEffect } from 'react'

/**
 * 네이버 인가 팝업이 되돌아오는 페이지. URL 쿼리의 code·state(또는 error)를 여는 창(opener)으로
 * 보내고 팝업을 닫는다. 실제 토큰 교환·조회는 백엔드가 한다.
 */
export default function NaverCallback() {
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const code = params.get('code')
    const state = params.get('state')
    const error = params.get('error')
    const message = code
      ? { type: 'NAVER_AUTH' as const, code, state: state ?? '' }
      : { type: 'NAVER_AUTH' as const, error: error || '인가 코드를 받지 못했습니다.' }

    if (window.opener) window.opener.postMessage(message, window.location.origin)
    window.close()
  }, [])

  return <div style={{ padding: 24, textAlign: 'center' }}>네이버 로그인 처리 중입니다…</div>
}

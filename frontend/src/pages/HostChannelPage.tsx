import * as PortOne from '@portone/browser-sdk/v2'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { expoApi } from '../api/expo'
import type { ApplyPromotionResponse } from '../api/expo'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../components/Toast'
import { usePageTitle } from '../hooks/usePageTitle'
import { expoKey } from '../types'
import type { Channel, Expo } from '../types'

export default function HostChannelPage() {
  const { isRole } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()

  const [channel, setChannel] = useState<Channel | null>(null)
  const [expos, setExpos] = useState<Expo[]>([])
  const [loadingCh, setLoadingCh] = useState(true)
  const [loadingEx, setLoadingEx] = useState(false)
  const [activePromos, setActivePromos] = useState<Record<number, number>>({})
  const [promoLoading, setPromoLoading] = useState<Record<number, boolean>>({})
  usePageTitle(channel?.name ? `${channel.name} - 주최자 센터` : '주최자 센터')

  function loadExpos(channelId: number) {
    setLoadingEx(true)
    // 주최자 목록이라 HIDDEN·CLOSED 도 함께 온다. 공개 목록을 거르면 비공개가 통째로 빠진다.
    expoApi.listMyExpos(channelId)
      .then(res => setExpos(res.data ?? []))
      .catch(() => toast('박람회 목록을 불러오지 못했습니다', 'error'))
      .finally(() => setLoadingEx(false))
  }

  function loadActivePromos() {
    expoApi.getActivePromotions()
      .then(res => {
        const map: Record<number, number> = {}
        for (const p of (res.data ?? [])) map[Number(p.expoId)] = p.promotionId
        setActivePromos(map)
      })
      .catch(() => {})
  }

  useEffect(() => {
    if (!isRole('ORGANIZER', 'SUPER_ADMIN')) {
      toast('주최자 권한이 필요합니다', 'error')
      navigate('/')
    }
  }, [])

  useEffect(() => {
    expoApi.getMyChannel()
      .then(res => {
        if (res.data) {
          setChannel(res.data)
          loadExpos(res.data.id)
        }
      })
      .catch((err: { status?: number }) => {
        // 404 = 아직 채널 없음, 오류가 아님
        if (err.status !== 404) toast('채널 정보를 불러오지 못했습니다', 'error')
      })
      .finally(() => setLoadingCh(false))

    loadActivePromos()
  }, [])

  async function handleApply(expoId: number) {
    setPromoLoading(p => ({ ...p, [expoId]: true }))
    try {
      const res = await expoApi.applyPromotion(expoId)
      const data = res.data as ApplyPromotionResponse

      const response = await PortOne.requestPayment({
        storeId: import.meta.env.VITE_PORTONE_STORE_ID,
        channelKey: import.meta.env.VITE_PORTONE_CHANNEL_KEY,
        paymentId: data.paymentId,
        orderName: 'VIP 배너 노출 (9,900원)',
        totalAmount: 9900,
        currency: 'CURRENCY_KRW',
        payMethod: 'CARD',
        // ReservationModal 과 동일한 이유로 IFRAME 고정 - PG 기본값에 맡기면 모바일에서
        // POPUP(화면 전체를 차지)으로 열릴 수 있다.
        windowType: { pc: 'IFRAME', mobile: 'IFRAME' },
      })

      if (response?.code) {
        toast(response.message ?? '결제가 취소되었습니다', 'error')
        return
      }

      // 서버가 PG 에 결제 사실과 금액을 직접 확인한 뒤에만 배너가 켜진다
      const confirmed = await expoApi.confirmPromotionPayment(data.promotionId)
      if (confirmed.data.status !== 'ACTIVE') {
        toast('결제가 완료되지 않았습니다', 'error')
        return
      }

      toast('VIP 배너 신청이 완료되었습니다!', 'success')
      loadActivePromos()
    } catch (err: unknown) {
      const code = (err as { body?: { data?: { code?: string } } })?.body?.data?.code
      if (code === 'PROMOTION_SLOT_FULL') toast('VIP 배너 슬롯이 모두 사용 중입니다. 잠시 후 다시 시도해주세요.', 'error')
      else if (code === 'PROMOTION_ALREADY_EXISTS') toast('이미 진행 중인 배너 신청이 있습니다', 'error')
      else if (code === 'PROMOTION_EXPO_NOT_PUBLISHED') toast('공개 중인 박람회만 VIP 배너를 신청할 수 있습니다', 'error')
      else if (code === 'DEPENDENCY_UNAVAILABLE') toast('결제 확인이 지연되고 있습니다. 잠시 후 목록을 새로고침해 주세요', 'error')
      else toast('배너 신청에 실패했습니다', 'error')
    } finally {
      setPromoLoading(p => ({ ...p, [expoId]: false }))
    }
  }

  async function handleRefund(expoId: number, promotionId: number) {
    setPromoLoading(p => ({ ...p, [expoId]: true }))
    try {
      await expoApi.refundPromotion(promotionId)
      toast('VIP 배너가 환불되었습니다', 'success')
      setActivePromos(p => { const next = { ...p }; delete next[expoId]; return next })
    } catch {
      toast('환불에 실패했습니다', 'error')
    } finally {
      setPromoLoading(p => ({ ...p, [expoId]: false }))
    }
  }

  return (
    <div style={{ background: 'var(--bg)', minHeight: 'calc(100vh - 64px)' }}>
      <div className="container page-wrap">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 32 }}>
          <div className="page-header" style={{ marginBottom: 0 }}>
            <h1 className="page-title">주최자 센터</h1>
            <p className="page-sub">내 채널과 소속 박람회를 관리하세요.</p>
          </div>
          {!channel && !loadingCh && (
            <button className="btn btn-primary" onClick={() => navigate('/host/channel/new')}>
              + 채널 생성
            </button>
          )}
        </div>

        {loadingCh ? (
          <p style={{ color: 'var(--sub)', textAlign: 'center', padding: '60px 0' }}>불러오는 중...</p>
        ) : !channel ? (
          <div className="empty-state">
            <p className="es-title">채널이 없습니다</p>
            <p className="es-desc">채널을 먼저 만들어야 박람회를 등록할 수 있습니다.</p>
            <button className="btn btn-primary" onClick={() => navigate('/host/channel/new')}>
              채널 만들기
            </button>
          </div>
        ) : (
          <div>
            <div className="section-header">
              <div>
                <span className="section-title">{channel.name}</span>
                {channel.description && (
                  <p style={{ fontSize: 13, color: 'var(--sub)', marginTop: 4 }}>{channel.description}</p>
                )}
              </div>
              <button
                className="btn btn-primary btn-sm"
                onClick={() => navigate(`/host/expos/new?channelId=${channel.id}`)}
              >
                + 박람회 등록
              </button>
            </div>

            {loadingEx ? (
              <p style={{ color: 'var(--sub)', padding: '40px 0' }}>불러오는 중...</p>
            ) : expos.length === 0 ? (
              <div className="empty-state">
                <p className="es-title">등록된 박람회가 없습니다</p>
                <p className="es-desc">첫 번째 박람회를 등록해보세요.</p>
                <button
                  className="btn btn-primary"
                  onClick={() => navigate(`/host/expos/new?channelId=${channel.id}`)}
                >
                  박람회 등록하기
                </button>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {expos.map(expo => {
                  const eid = expoKey(expo)
                  const promoId = activePromos[eid]
                  const busy = promoLoading[eid] ?? false
                  return (
                    <ExpoRow
                      key={eid}
                      expo={expo}
                      hasActivePromo={promoId != null}
                      promoLoading={busy}
                      onManage={() => navigate(`/host/expos/${eid}/rounds`, { state: { expo } })}
                      onEdit={() => navigate(`/host/expos/${eid}/edit`)}
                      onApplyPromo={() => handleApply(eid)}
                      onRefundPromo={() => handleRefund(eid, promoId)}
                    />
                  )
                })}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function ExpoRow({
  expo, hasActivePromo, promoLoading, onManage, onEdit, onApplyPromo, onRefundPromo,
}: {
  expo: Expo
  hasActivePromo: boolean
  promoLoading: boolean
  onManage: () => void
  onEdit: () => void
  onApplyPromo: () => void
  onRefundPromo: () => void
}) {
  return (
    <div
      className="card host-expo-card"
      style={{ padding: '18px 22px', display: 'flex', alignItems: 'center', gap: 16 }}
    >
      <div
        style={{
          width: 48, height: 48,
          borderRadius: 'var(--r-sm)',
          background: 'linear-gradient(135deg, var(--navy), #2E3A5C)',
          flexShrink: 0, cursor: 'pointer',
        }}
        onClick={onManage}
      />

      <div style={{ flex: 1, minWidth: 0, cursor: 'pointer' }} onClick={onManage}>
        <div style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
          <span className={`badge badge-${(expo.status ?? 'PUBLISHED').toLowerCase()}`}>
            {(expo.status ?? 'PUBLISHED') === 'PUBLISHED' ? '● 공개중' : expo.status === 'HIDDEN' ? '○ HIDDEN' : '● 종료'}
          </span>
          <span className="badge badge-blue">{expo.category}</span>
          {hasActivePromo && (
            <span className="badge" style={{ background: '#7C3AED', color: '#fff' }}>⭐ VIP</span>
          )}
        </div>
        <h3 style={{
          fontWeight: 700, color: 'var(--text)', fontSize: 15,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {expo.title}
        </h3>
        {(expo.region || expo.venue) && (
          <p style={{ fontSize: 12, color: 'var(--sub)', marginTop: 2 }}>
            {[expo.venue, expo.region].filter(Boolean).join(' · ')}
          </p>
        )}
      </div>

      <div className="expo-row-actions" style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
        {hasActivePromo ? (
          <button
            className="btn btn-sm btn-secondary"
            disabled={promoLoading}
            onClick={onRefundPromo}
          >
            {promoLoading ? '처리중...' : 'VIP 환불'}
          </button>
        ) : (expo.status ?? 'PUBLISHED') === 'PUBLISHED' && (
          <button
            className="btn btn-sm"
            style={{ background: '#7C3AED', color: '#fff', border: 'none' }}
            disabled={promoLoading}
            onClick={onApplyPromo}
          >
            {promoLoading ? '처리중...' : '⭐ VIP 배너 신청'}
          </button>
        )}
        <button className="btn btn-secondary btn-sm" onClick={onEdit}>
          정보 수정
        </button>
        <button
          className="btn btn-secondary btn-sm"
          onClick={onManage}
        >
          회차 관리 →
        </button>
      </div>
    </div>
  )
}

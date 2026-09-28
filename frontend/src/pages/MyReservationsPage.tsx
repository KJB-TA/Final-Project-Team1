import { useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { reservationApi } from '../api/reservation'
import { useToast } from '../components/Toast'
import { usePageTitle } from '../hooks/usePageTitle'
import type { MyReservation, MyReservationDetail } from '../types'

function fmtDateTime(dt?: string) {
  if (!dt) return '-'
  return new Date(dt).toLocaleString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const STATUS_LABEL: Record<MyReservation['status'], string> = {
  PENDING: '결제 대기',
  CONFIRMED: '예약 확정',
  CANCELLED: '취소됨',
  EXPIRED: '기한 만료',
}
const STATUS_BADGE: Record<MyReservation['status'], string> = {
  PENDING: 'badge-hidden',
  CONFIRMED: 'badge-published',
  CANCELLED: 'badge-closed',
  EXPIRED: 'badge-closed',
}

const REFUND_LABEL: Record<string, string> = {
  REFUNDED: '환불 완료',
  REFUND_PENDING: '환불 처리 중',
  REFUND_UNRESOLVED: '환불 지연 — 문의 필요',
  NOT_REFUNDABLE: '환불 기한 경과(환불 불가)',
}

/** 환불 창. 서버의 reservation.cancellation.refund-window(1d) 와 같은 값이어야 한다. */
const REFUND_WINDOW_MS = 24 * 60 * 60 * 1000

// 취소는 기한 안이면 늘 성공하므로, 돈이 돌아갔는지는 응답의 refundState 로만 알 수 있다.
const CANCEL_TOAST: Record<string, string> = {
  REFUNDED: '예약이 취소되고 환불되었습니다',
  REFUND_PENDING: '예약이 취소되었습니다. 환불은 처리 중입니다',
  REFUND_UNRESOLVED: '예약이 취소되었으나 환불이 지연되고 있습니다. 문의해주세요',
  NOT_REFUNDABLE: '예약이 취소되었습니다 (환불 기한이 지나 환불되지 않습니다)',
}

const CANCEL_ERROR_MESSAGES: Record<string, string> = {
  CANCELLATION_DEADLINE_PASSED: '회차가 이미 시작되어 취소할 수 없습니다.',
  ALREADY_CHECKED_IN: '이미 현장 입장이 완료된 예약은 취소할 수 없습니다.',
  DEPENDENCY_UNAVAILABLE: '티켓 상태를 확인하지 못했습니다. 잠시 후 다시 시도해주세요.',
  NOT_FOUND: '예약 정보를 찾을 수 없습니다.',
  INVALID_STATE_TRANSITION: '이미 종료된 예약은 취소할 수 없습니다.',
}

function cancelErrorMessage(e: unknown, fallback: string) {
  const code = (e as { body?: { data?: { code?: string } } } | undefined)?.body?.data?.code
  return (code && CANCEL_ERROR_MESSAGES[code]) || fallback
}

export default function MyReservationsPage() {
  usePageTitle('내 예약')
  const toast = useToast()
  const [reservations, setReservations] = useState<MyReservation[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [detailId, setDetailId] = useState<number | null>(null)

  const fetchReservations = () => {
    reservationApi.listMine()
      .then(res => { setReservations(res.data ?? []); setError(false) })
      .catch(() => setError(true))
      .finally(() => setLoading(false))
  }

  useEffect(fetchReservations, [])

  const reload = () => {
    setLoading(true)
    fetchReservations()
  }

  const handleCancelled = (reservationId: number, status: string, refundState: string) => {
    setReservations(prev => prev.map(r =>
      r.reservationId === reservationId ? { ...r, status: status as MyReservation['status'], refundState: refundState as MyReservation['refundState'] } : r
    ))
    toast(CANCEL_TOAST[refundState] ?? '예약이 취소되었습니다', 'success')
  }

  return (
    <div className="container page-wrap">
      <div className="page-header">
        <h1 className="page-title">내 예약</h1>
        <p className="page-sub">신청한 예약을 확인하고, 취소·환불을 처리할 수 있습니다.</p>
      </div>

      {loading ? (
        <p style={{ color: 'var(--sub)', textAlign: 'center', padding: '60px 0' }}>불러오는 중...</p>
      ) : error ? (
        <div className="empty-state">
          <p className="es-title">불러올 수 없습니다</p>
          <p className="es-desc">잠시 후 다시 시도해주세요.</p>
          <button className="btn btn-outline" onClick={reload}>새로고침</button>
        </div>
      ) : reservations.length === 0 ? (
        <div className="empty-state">
          <p className="es-title">예약 내역이 없습니다</p>
          <p className="es-desc">박람회를 둘러보고 회차를 예약해보세요.</p>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {reservations.map(r => (
            <div
              key={r.reservationId}
              className="card"
              style={{ padding: '18px 22px', display: 'flex', alignItems: 'center', gap: 16, cursor: 'pointer' }}
              onClick={() => setDetailId(r.reservationId)}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
                  <span className={`badge ${STATUS_BADGE[r.status]}`}>{STATUS_LABEL[r.status]}</span>
                  {REFUND_LABEL[r.refundState] && (
                    <span className="badge badge-blue">{REFUND_LABEL[r.refundState]}</span>
                  )}
                </div>
                <div style={{ fontWeight: 700, color: 'var(--text)', marginBottom: 2 }}>
                  {r.expoTitle || `박람회 #${r.expoId}`}
                  {r.roundSequence && (
                    <span style={{ marginLeft: 6, fontSize: 13, fontWeight: 600, color: 'var(--primary)' }}>
                      {r.roundSequence}회차
                    </span>
                  )}
                </div>
                <div style={{ fontSize: 13, color: 'var(--text2)', marginBottom: 2 }}>
                  {fmtDateTime(r.startsAt)} – {fmtDateTime(r.endsAt)}
                </div>
                <div style={{ fontSize: 12, color: 'var(--sub)' }}>
                  예약번호 {r.reservationNo} · {r.headcount}명 {r.amount > 0 && `· ${r.amount.toLocaleString()}원`}
                </div>
              </div>
              <button className="btn btn-secondary btn-sm" onClick={(e) => { e.stopPropagation(); setDetailId(r.reservationId) }}>
                상세보기
              </button>
            </div>
          ))}
        </div>
      )}

      {detailId != null && (
        <ReservationDetailModal
          reservationId={detailId}
          onClose={() => setDetailId(null)}
          onCancelled={handleCancelled}
        />
      )}
    </div>
  )
}

function ReservationDetailModal({ reservationId, onClose, onCancelled }: {
  reservationId: number
  onClose: () => void
  onCancelled: (reservationId: number, status: string, refundState: string) => void
}) {
  const toast = useToast()
  const [detail, setDetail] = useState<MyReservationDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [cancelling, setCancelling] = useState(false)
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const [noRefund, setNoRefund] = useState(false)

  useEffect(() => {
    reservationApi.getMine(reservationId)
      .then(res => setDetail(res.data))
      .catch(() => toast('상세 정보를 불러오지 못했습니다', 'error'))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reservationId])

  const handleCancel = async () => {
    setCancelling(true)
    try {
      const result = (await reservationApi.cancel(reservationId)).data
      setDetail(prev => prev && { ...prev, status: result.status as MyReservationDetail['status'], refundState: result.refundState })
      onCancelled(reservationId, result.status, result.refundState)
      setConfirmingCancel(false)
    } catch (e) {
      toast(cancelErrorMessage(e, '취소에 실패했습니다. 잠시 후 다시 시도해주세요.'), 'error')
    } finally {
      setCancelling(false)
    }
  }

  const cancellable = detail?.status === 'PENDING' || detail?.status === 'CONFIRMED'

  // 결제 대기(PENDING)는 받은 돈이 없어 기한과 무관하게 돌려주므로, 확정된 유료 예약만 경고한다.
  // 시각은 취소 버튼을 누른 순간 기준이다.
  const startCancel = () => {
    setNoRefund(detail?.status === 'CONFIRMED' && detail.amount > 0 && !!detail.startsAt
      && new Date(detail.startsAt).getTime() - Date.now() < REFUND_WINDOW_MS)
    setConfirmingCancel(true)
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3 className="modal-title">예약 상세</h3>

        {loading ? (
          <p style={{ color: 'var(--sub)', padding: '20px 0' }}>불러오는 중...</p>
        ) : !detail ? (
          <p style={{ color: 'var(--sub)', padding: '20px 0' }}>정보를 불러올 수 없습니다.</p>
        ) : (
          <>
            <div style={{ display: 'flex', gap: 6, marginBottom: 14 }}>
              <span className={`badge ${STATUS_BADGE[detail.status]}`}>{STATUS_LABEL[detail.status]}</span>
              {REFUND_LABEL[detail.refundState] && (
                <span className="badge badge-blue">{REFUND_LABEL[detail.refundState]}</span>
              )}
            </div>

            <div className="form-group">
              <label className="form-label">박람회</label>
              <p>
                {detail.expoTitle || `박람회 #${detail.expoId}`}
                {detail.roundSequence && ` · ${detail.roundSequence}회차`}
              </p>
            </div>
            <div className="form-group">
              <label className="form-label">예약번호</label>
              <p>{detail.reservationNo}</p>
            </div>
            <div className="form-group">
              <label className="form-label">일시</label>
              <p>{fmtDateTime(detail.startsAt)} – {fmtDateTime(detail.endsAt)}</p>
            </div>
            <div className="form-group">
              <label className="form-label">예약자</label>
              <p>{detail.contactName} · {detail.contactPhone}</p>
            </div>
            <div className="form-group">
              <label className="form-label">인원 / 결제 금액</label>
              <p>{detail.headcount}명{detail.amount > 0 && ` · ${detail.amount.toLocaleString()}원`}</p>
            </div>

            {detail.status === 'CONFIRMED' && (
              <div className="form-group">
                <label className="form-label">입장용 QR 티켓</label>
                {detail.ticketAvailable && detail.ticket ? (
                  detail.ticket.status === 'USED' ? (
                    <p style={{ fontSize: 14, fontWeight: 600, color: 'var(--sub)' }}>입장 완료</p>
                  ) : detail.ticket.status === 'CANCELLED' ? (
                    <p style={{ fontSize: 14, fontWeight: 600, color: 'var(--sub)' }}>무효화된 티켓입니다.</p>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '8px 0' }}>
                      <div style={{ background: '#fff', padding: 12, borderRadius: 'var(--r-sm)' }}>
                        <QRCodeSVG value={detail.ticket.checkinToken} size={180} level="M" />
                      </div>
                      <p style={{ fontFamily: 'monospace', fontSize: 11, wordBreak: 'break-all', color: 'var(--sub)' }}>
                        {detail.ticket.checkinToken}
                      </p>
                      <p style={{ fontSize: 12, color: 'var(--sub)' }}>현장에서 이 QR을 제시하세요.</p>
                    </div>
                  )
                ) : (
                  <p style={{ fontSize: 13, color: 'var(--sub)' }}>티켓이 아직 발급되지 않았습니다. 잠시 후 다시 확인해주세요.</p>
                )}
              </div>
            )}

            {confirmingCancel ? (
              <div className="alert alert-warning" style={{ marginBottom: 4 }}>
                {/* .alert 는 flex 라 자식이 가로로 갈린다. 한 덩어리로 묶어 세로로 쌓는다. */}
                <div style={{ wordBreak: 'keep-all' }}>
                  {noRefund && (
                    <>
                      <p style={{ fontWeight: 700 }}>지금 취소하면 환불되지 않습니다.</p>
                      <p style={{ marginBottom: 8 }}>환불은 회차 시작 24시간 전까지 취소한 경우에만 됩니다.</p>
                    </>
                  )}
                  <p>정말 예약을 취소하시겠습니까? 이 작업은 되돌릴 수 없습니다.</p>
                </div>
              </div>
            ) : null}

            <div className="modal-footer">
              {confirmingCancel ? (
                <>
                  <button className="btn btn-secondary" onClick={() => setConfirmingCancel(false)} disabled={cancelling}>
                    아니오
                  </button>
                  <button className="btn btn-danger" onClick={handleCancel} disabled={cancelling}>
                    {cancelling ? '처리 중...' : '취소 확정'}
                  </button>
                </>
              ) : (
                <>
                  <button className="btn btn-secondary" onClick={onClose}>닫기</button>
                  {cancellable && (
                    <button className="btn btn-danger" onClick={startCancel}>
                      예약 취소
                    </button>
                  )}
                </>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}

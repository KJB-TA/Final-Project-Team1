import { useState } from 'react'
import { ticketApi } from '../api/ticket'
import type { CheckinReport } from '../api/ticket'
import { useToast } from './Toast'

/**
 * 현장 체크인 결과 요약(#259).
 *
 * 자동으로 부르지 않는다. 행사가 끝난 뒤 주최자가 눌러야 집계와 LLM 호출이 일어난다.
 */
export default function CheckinReportCard({ expoId }: { expoId: number }) {
  const toast = useToast()
  const [report, setReport] = useState<CheckinReport | null>(null)
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      setReport((await ticketApi.checkinReport(expoId)).data)
    } catch (e: unknown) {
      const status = (e as { status?: number })?.status
      if (status === 403) toast('이 박람회의 주최자만 볼 수 있습니다', 'error')
      else toast('체크인 요약을 불러오지 못했습니다', 'error')
    } finally {
      setLoading(false)
    }
  }

  const peak = report?.hourly.length
    ? report.hourly.reduce((a, b) => (b.count > a.count ? b : a))
    : null

  return (
    <div className="card" style={{ padding: '20px 24px', marginBottom: 28 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
        <div>
          <span className="section-title" style={{ fontSize: 15 }}>현장 체크인 결과</span>
          <p style={{ fontSize: 12, color: 'var(--sub)', marginTop: 4 }}>
            모든 회차의 입장 기록을 합산해 요약하고, 회차별 내역을 함께 보여드립니다. 행사가 끝난 뒤 확인하세요.
          </p>
        </div>
        <button className="btn btn-outline btn-sm" onClick={load} disabled={loading}>
          {loading ? '불러오는 중...' : report ? '다시 불러오기' : '요약 보기'}
        </button>
      </div>

      {report && (
        <div style={{ marginTop: 18 }}>
          {report.summary && (
            <div className="alert" style={{
              background: 'var(--teal-l)', color: 'var(--text)', marginBottom: 16,
              display: 'flex', gap: 10, alignItems: 'flex-start',
            }}>
              <span style={{ fontWeight: 800, color: 'var(--teal)', flexShrink: 0 }}>AI</span>
              <span style={{ lineHeight: 1.6 }}>{report.summary}</span>
            </div>
          )}

          <p style={{ fontSize: 13, fontWeight: 700, color: 'var(--text)', marginBottom: 10 }}>
            박람회 전체
            {report.rounds.length > 0 && (
              <span style={{ fontWeight: 500, color: 'var(--sub)' }}> · {report.rounds.length}개 회차 합산</span>
            )}
          </p>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 24, marginBottom: 14 }}>
            <Stat label="예약 확정" value={`${report.reserved}명`} />
            <Stat label="실제 입장" value={`${report.checkedIn}명`} accent />
            <Stat label="미입장" value={`${report.noShow}명`} />
            <Stat label="입장률" value={`${report.checkinRate}%`} accent />
            {peak && <Stat label="가장 붐빈 시간" value={`${fmtMonthDay(peak.date)} ${peak.hour}시 (${peak.count}건)`} />}
            {report.reverted > 0 && <Stat label="되돌린 체크인" value={`${report.reverted}건`} />}
          </div>

          {report.rounds.length > 0 && (
            <div style={{ marginBottom: 14 }}>
              <p style={{ fontSize: 13, fontWeight: 700, color: 'var(--text)', marginBottom: 8 }}>회차별</p>
              <div className="table-wrap" style={{ border: '1px solid var(--border)', borderRadius: 'var(--r-sm)' }}>
                <table>
                  <thead>
                    <tr>
                      <th>회차</th>
                      <th>일시</th>
                      <th style={{ textAlign: 'right' }}>예약 확정</th>
                      <th style={{ textAlign: 'right' }}>입장</th>
                      <th style={{ textAlign: 'right' }}>미입장</th>
                      <th style={{ textAlign: 'right' }}>입장률</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.rounds.map(r => (
                      <tr key={r.roundId}>
                        <td style={{ fontWeight: 700, color: 'var(--text)' }}>
                          {r.sequence ? `${r.sequence}회차` : `회차 #${r.roundId}`}
                        </td>
                        <td>{fmtRoundStart(r.startsAt)}</td>
                        <td style={{ textAlign: 'right' }}>{r.reserved}명</td>
                        <td style={{ textAlign: 'right' }}>{r.checkedIn}명</td>
                        <td style={{ textAlign: 'right' }}>{r.noShow}명</td>
                        <td style={{ textAlign: 'right', fontWeight: 700, color: 'var(--primary)' }}>
                          {r.reserved > 0 ? `${r.checkinRate}%` : '-'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {Object.keys(report.byMethod).length > 0 && (
            <p style={{ fontSize: 13, color: 'var(--sub)' }}>
              처리 방법 · {Object.entries(report.byMethod)
                .map(([method, count]) => `${methodLabel(method)} ${count}건`)
                .join(' / ')}
            </p>
          )}

          {report.checkedIn === 0 && (
            <p style={{ fontSize: 13, color: 'var(--sub)' }}>아직 입장 기록이 없습니다.</p>
          )}
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div>
      <p style={{ fontSize: 12, color: 'var(--sub)', marginBottom: 2 }}>{label}</p>
      <p style={{
        fontSize: 18, fontWeight: 800,
        color: accent ? 'var(--primary)' : 'var(--text)',
      }}>{value}</p>
    </div>
  )
}

/** "2026-11-20" → "11/20". 서버가 한국 시간 기준 날짜를 주므로 시간대 변환을 하지 않는다. */
function fmtMonthDay(date: string) {
  const [, m, d] = date.split('-')
  return `${Number(m)}/${Number(d)}`
}

function fmtRoundStart(startsAt: string | null) {
  if (!startsAt) return '-'
  return new Date(startsAt).toLocaleString('ko-KR', {
    month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit',
  })
}

function methodLabel(method: string) {
  if (method === 'QR') return 'QR'
  if (method === 'RESERVATION_NO') return '예약번호'
  return '미기록'
}

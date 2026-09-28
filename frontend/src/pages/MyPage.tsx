import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { userApi } from '../api/user'
import { organizerRequestApi, type OrganizerApplicationResponse } from '../api/organizerRequest'
import { recommendationApi, type Interests } from '../api/recommendation'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../components/Toast'
import { usePageTitle } from '../hooks/usePageTitle'
import Avatar from '../components/Avatar'
import { uploadImage, UploadError } from '../lib/cloudinary'

export default function MyPage() {
  usePageTitle('마이페이지')
  const { user, login, isRole } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  // 소셜 가입 회원은 비밀번호가 없어 변경 카드가 의미 없다. 모르는 동안(null)은 보여준다.
  const [hasPassword, setHasPassword] = useState<boolean | null>(null)

  useEffect(() => {
    if (!user) return
    userApi.getMe()
      .then(res => setHasPassword(res.data.hasPassword))
      .catch(() => {})
  }, [])

  useEffect(() => {
    if (!user) {
      toast('로그인이 필요합니다', 'error')
      navigate('/auth')
    }
  }, [])

  if (!user) return null

  return (
    <div style={{ background: 'var(--bg)', minHeight: 'calc(100vh - 64px)' }}>
      <div className="container page-wrap">
        <div style={{ maxWidth: 480, margin: '0 auto' }}>
          <div className="page-header">
            <h1 className="page-title">마이페이지</h1>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
            <ProfileImageCard />
            <ProfileCard />
            <ChangeNameCard onChanged={name => login({ ...user, name })} />
            {hasPassword !== false && <ChangePasswordCard />}
            {isRole('USER') && <InterestsCard />}
            {isRole('USER') && <OrganizerRequestCard />}
          </div>
        </div>
      </div>
    </div>
  )
}

function ProfileImageCard() {
  const { user, login } = useAuth()
  const toast = useToast()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)

  useEffect(() => {
    if (!user) return
    userApi.getMe()
      .then(res => login({ ...user, name: res.data.name, profileImageUrl: res.data.profileImageUrl }))
      .catch(() => {})
  }, [])

  if (!user) return null

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return

    setUploading(true)
    try {
      const imageUrl = await uploadImage(file)
      const res = await userApi.changeProfileImage(imageUrl)
      login({ ...user!, profileImageUrl: res.data.profileImageUrl })
      toast('프로필 사진이 변경되었습니다 ✓', 'success')
    } catch (err) {
      toast(err instanceof UploadError ? err.message : '이미지 변경에 실패했습니다', 'error')
    } finally {
      setUploading(false)
    }
  }

  return (
    <div className="card" style={{ padding: 32, display: 'flex', alignItems: 'center', gap: 20 }}>
      <Avatar userId={user.id} name={user.name} imageUrl={user.profileImageUrl} size={72} />
      <div>
        <h2 style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)', marginBottom: 4 }}>
          프로필 사진
        </h2>
        <p style={{ fontSize: 12.5, color: 'var(--sub)', marginBottom: 12 }}>
          JPG · PNG · WEBP · GIF, 10MB 이하
        </p>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/webp,image/gif"
          style={{ display: 'none' }}
          onChange={handleFileChange}
        />
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          disabled={uploading}
          onClick={() => fileInputRef.current?.click()}
        >
          {uploading ? '업로드 중...' : '사진 변경'}
        </button>
      </div>
    </div>
  )
}

function ProfileCard() {
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    userApi.getMe()
      .then(res => setEmail(res.data.email))
      .catch(() => toast('내 정보를 불러오지 못했습니다', 'error'))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="card" style={{ padding: 32 }}>
      <h2 style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)', marginBottom: 16 }}>
        기본 정보
      </h2>
      <div className="form-group" style={{ marginBottom: 0 }}>
        <label className="form-label">이메일</label>
        <input className="form-input" type="email" value={loading ? '불러오는 중...' : email} disabled />
      </div>
    </div>
  )
}

function ChangeNameCard({ onChanged }: { onChanged: (name: string) => void }) {
  const toast = useToast()
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(false)
  const [checking, setChecking] = useState(false)
  const [checkResult, setCheckResult] = useState<'available' | 'taken' | null>(null)

  function handleNameChange(value: string) {
    setName(value)
    setCheckResult(null)
  }

  async function handleCheck() {
    setChecking(true)
    try {
      const res = await userApi.checkNameAvailability(name)
      setCheckResult(res.data.available ? 'available' : 'taken')
    } catch {
      toast('중복 확인에 실패했습니다', 'error')
    } finally {
      setChecking(false)
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    try {
      const res = await userApi.changeName(name)
      onChanged(res.data.name)
      toast('닉네임이 변경되었습니다 ✓', 'success')
      setName('')
      setCheckResult(null)
    } catch (err: unknown) {
      const e = err as { status?: number }
      toast(e.status === 409 ? '이미 사용 중인 닉네임입니다.' : '닉네임 변경에 실패했습니다', 'error')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="card" style={{ padding: 32 }}>
      <h2 style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)', marginBottom: 16 }}>
        닉네임 변경
      </h2>
      <form onSubmit={handleSubmit}>
        <div className="form-group" style={{ marginBottom: checkResult ? 4 : 0 }}>
          <label className="form-label">새 닉네임</label>
          <div className="nickname-form-row" style={{ display: 'flex', gap: 12, alignItems: 'flex-end' }}>
            <input
              className="form-input"
              style={{ flex: 1 }}
              type="text"
              placeholder="새 닉네임을 입력하세요"
              value={name}
              onChange={e => handleNameChange(e.target.value)}
              required
            />
            <button type="button" className="btn btn-secondary" disabled={!name || checking} onClick={handleCheck}>
              {checking ? '확인 중...' : '중복확인'}
            </button>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? '변경 중...' : '변경'}
            </button>
          </div>
        </div>
        {checkResult === 'available' && (
          <p style={{ fontSize: 12, color: 'var(--green)', marginTop: 6 }}>사용 가능한 닉네임입니다.</p>
        )}
        {checkResult === 'taken' && (
          <p style={{ fontSize: 12, color: 'var(--red)', marginTop: 6 }}>이미 사용 중인 닉네임입니다.</p>
        )}
      </form>
    </div>
  )
}

function ChangePasswordCard() {
  const toast = useToast()
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const mismatch = form.confirm.length > 0 && form.newPassword !== form.confirm

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    if (form.newPassword !== form.confirm) {
      setError('새 비밀번호가 일치하지 않습니다.')
      return
    }
    setLoading(true)
    try {
      await userApi.changePassword(form.currentPassword, form.newPassword)
      toast('비밀번호가 변경되었습니다 ✓', 'success')
      setForm({ currentPassword: '', newPassword: '', confirm: '' })
    } catch (err: unknown) {
      const e = err as { status?: number }
      setError(e.status === 401 ? '현재 비밀번호가 올바르지 않습니다.' : '비밀번호 변경에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="card" style={{ padding: 32 }}>
      <h2 style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)', marginBottom: 16 }}>
        비밀번호 변경
      </h2>

      {error && <div className="alert alert-danger" style={{ marginBottom: 16 }}><span>⚠</span><span>{error}</span></div>}

      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label">현재 비밀번호 <span className="req">*</span></label>
          <input
            className="form-input"
            type="password"
            value={form.currentPassword}
            onChange={e => setForm(p => ({ ...p, currentPassword: e.target.value }))}
            autoComplete="current-password"
            required
          />
        </div>
        <div className="form-group">
          <label className="form-label">새 비밀번호 <span className="req">*</span></label>
          <input
            className="form-input"
            type="password"
            value={form.newPassword}
            onChange={e => setForm(p => ({ ...p, newPassword: e.target.value }))}
            autoComplete="new-password"
            required
          />
          <p className="form-hint">영문과 숫자를 포함해 8~64자</p>
        </div>
        <div className="form-group" style={{ marginBottom: 24 }}>
          <label className="form-label">새 비밀번호 확인 <span className="req">*</span></label>
          <input
            className={`form-input ${mismatch ? 'error' : ''}`}
            type="password"
            value={form.confirm}
            onChange={e => setForm(p => ({ ...p, confirm: e.target.value }))}
            autoComplete="new-password"
            required
          />
          {mismatch && <p className="form-error">비밀번호가 일치하지 않습니다.</p>}
        </div>
        <button type="submit" className="btn btn-primary btn-block" disabled={loading}>
          {loading ? '변경 중...' : '비밀번호 변경'}
        </button>
      </form>
    </div>
  )
}

const CATS = ['IT·전자', '식품·음료', '패션·뷰티', '교육·취업', '문화·예술', '기타']

function InterestsCard() {
  const toast = useToast()
  const navigate = useNavigate()
  const [interests, setInterests] = useState<Interests>({ categories: [], keywords: [] })
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [fetching, setFetching] = useState(true)

  useEffect(() => {
    recommendationApi.getInterests()
      .then(res => { if (res.data) setInterests(res.data) })
      .catch(() => {})
      .finally(() => setFetching(false))
  }, [])

  function toggleCategory(cat: string) {
    setInterests(prev => ({
      ...prev,
      categories: prev.categories.includes(cat)
        ? prev.categories.filter(c => c !== cat)
        : [...prev.categories, cat],
    }))
  }

  function addKeyword() {
    const kw = keyword.trim()
    if (!kw || interests.keywords.includes(kw)) return
    setInterests(prev => ({ ...prev, keywords: [...prev.keywords, kw] }))
    setKeyword('')
  }

  function removeKeyword(kw: string) {
    setInterests(prev => ({ ...prev, keywords: prev.keywords.filter(k => k !== kw) }))
  }

  async function handleSave() {
    setLoading(true)
    try {
      await recommendationApi.saveInterests(interests)
      toast('관심사가 저장되었습니다 ✓', 'success')
    } catch {
      toast('저장에 실패했습니다', 'error')
    } finally {
      setLoading(false)
    }
  }

  if (fetching) return null

  return (
    <div className="card" style={{ padding: 32 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
        <h2 style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)' }}>관심사 설정</h2>
        <button className="btn btn-secondary btn-sm" onClick={() => navigate('/my/recommendations')}>
          내 추천 보기 →
        </button>
      </div>
      <p style={{ fontSize: 13, color: 'var(--sub)', marginBottom: 20 }}>
        선택한 관심사로 맞춤 박람회를 추천해드립니다.
      </p>

      <div style={{ marginBottom: 20 }}>
        <p style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)', marginBottom: 10 }}>카테고리</p>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {CATS.map(cat => (
            <button
              key={cat}
              type="button"
              className={`cat-chip ${interests.categories.includes(cat) ? 'active' : ''}`}
              onClick={() => toggleCategory(cat)}
            >
              {cat}
            </button>
          ))}
        </div>
      </div>

      <div style={{ marginBottom: 24 }}>
        <p style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)', marginBottom: 10 }}>키워드</p>
        <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
          <input
            className="form-input"
            style={{ flex: 1 }}
            placeholder="예: AI, 스타트업, 친환경"
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && (e.preventDefault(), addKeyword())}
          />
          <button type="button" className="btn btn-secondary" onClick={addKeyword}>추가</button>
        </div>
        {interests.keywords.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {interests.keywords.map(kw => (
              <span key={kw} className="badge badge-blue" style={{ cursor: 'pointer' }} onClick={() => removeKeyword(kw)}>
                #{kw} ✕
              </span>
            ))}
          </div>
        )}
      </div>

      <button className="btn btn-primary btn-block" onClick={handleSave} disabled={loading}>
        {loading ? '저장 중...' : '관심사 저장'}
      </button>
    </div>
  )
}

function OrganizerRequestCard() {
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [loading, setLoading] = useState(false)
  const [checking, setChecking] = useState(true)
  const [application, setApplication] = useState<OrganizerApplicationResponse | null>(null)

  useEffect(() => {
    organizerRequestApi.getMyApplication()
      .then(res => setApplication(res.data))
      .catch(() => setApplication(null))
      .finally(() => setChecking(false))
  }, [])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    try {
      const res = await organizerRequestApi.submit(reason)
      setApplication(res.data)
      toast('주최자 신청이 접수되었습니다', 'success')
    } catch (err: unknown) {
      const e = err as { status?: number }
      toast(e.status === 409 ? '이미 처리 중인 신청이 있습니다.' : '신청에 실패했습니다. 잠시 후 다시 시도해주세요.', 'error')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="card" style={{ padding: 32 }}>
      <h2 style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)', marginBottom: 4 }}>
        주최자 계정 신청
      </h2>
      <p style={{ fontSize: 13, color: 'var(--sub)', marginBottom: 24 }}>
        신청하면 관리자 승인 후 주최자 권한으로 승격됩니다.
      </p>

      {checking ? null : application?.status === 'PENDING' ? (
        <div className="alert" style={{ background: 'var(--blue-l)', color: 'var(--blue)' }}>
          <span>⏳</span><span>신청이 접수되어 관리자 승인을 기다리고 있습니다.</span>
        </div>
      ) : application?.status === 'APPROVED' ? (
        <div className="alert" style={{ background: 'var(--green-l)', color: 'var(--green)' }}>
          <span>✓</span><span>승인되었습니다. 다시 로그인하면 주최자 권한이 적용됩니다.</span>
        </div>
      ) : (
        <>
          {application?.status === 'REJECTED' && (
            <div className="alert alert-danger" style={{ marginBottom: 16 }}>
              <span>⚠</span><span>지난 신청이 거절되었습니다{application.rejectReason ? `: ${application.rejectReason}` : ''}. 다시 신청할 수 있습니다.</span>
            </div>
          )}
          <form onSubmit={handleSubmit}>
            <div className="form-group" style={{ marginBottom: 24 }}>
              <label className="form-label">신청 사유</label>
              <textarea
                className="form-input"
                rows={3}
                placeholder="어떤 박람회를 주최하려고 하는지 간단히 적어주세요"
                value={reason}
                onChange={e => setReason(e.target.value)}
              />
            </div>
            <button type="submit" className="btn btn-primary btn-block" disabled={loading}>
              {loading ? '신청 중...' : '주최자 신청하기'}
            </button>
          </form>
        </>
      )}
    </div>
  )
}

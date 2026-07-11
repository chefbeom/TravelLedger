<script setup>
import { computed, reactive, ref } from 'vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import {
  changeProfilePassword,
  changeProfileSecondaryPin,
  cleanupPrivacySensitiveData,
  createSupportInquiry,
  deletePrivacyAiAnalysisHistory,
  downloadPrivacyDataExport,
  fetchMySupportInquiries,
  removePrivacyPhotoLocationMetadata,
  revokePrivacyPublicDownloadLinks,
  revokePrivacyTravelPublicMediaShares,
  verifyProfilePrivacyAccess,
  verifyProfileSecondaryPin,
} from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
  embedded: {
    type: Boolean,
    default: false,
  },
})

const PAGE_SIZE = 5
const attachmentInput = ref(null)
const activeProfileSection = ref('account')
const activeSupportSection = ref('create')

const state = reactive({
  loading: false,
  sending: false,
  errorMessage: '',
  successMessage: '',
  inquiries: [],
  pageInfo: {
    page: 0,
    size: PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
  },
  expandedInquiryId: null,
  historyLoaded: false,
})

const form = reactive({
  title: '',
  content: '',
  attachment: null,
})

const security = reactive({
  gateVisible: false,
  changeVisible: false,
  mode: 'password',
  verifying: false,
  saving: false,
  errorMessage: '',
  secondaryPin: '',
  verifiedSecondaryPin: '',
  newPassword: '',
  confirmPassword: '',
  newSecondaryPin: '',
  confirmSecondaryPin: '',
})

const privacy = reactive({
  modalVisible: false,
  verified: false,
  verifying: false,
  accessPassword: '',
  accessSecondaryPin: '',
  busyAction: '',
  errorMessage: '',
  successMessage: '',
  lastActionLabel: '',
  lastResult: null,
  exportFrom: '',
  exportTo: '',
  exportGateVisible: false,
  secondaryPin: '',
  exporting: false,
})

const statusLabel = {
  PENDING: '답변 대기',
  ANSWERED: '답변 완료',
}

const privacyActions = [
  {
    key: 'ai-history',
    label: 'AI 분석 이력 삭제',
    warning: '삭제된 AI 분석 이력은 복구할 수 없습니다.',
    resultLabel: 'AI 분석 이력 삭제',
    run: deletePrivacyAiAnalysisHistory,
  },
  {
    key: 'drive-links',
    label: '공개 드라이브 링크 회수',
    warning: '기존 공개 링크는 더 이상 사용할 수 없습니다. 소유자가 확인할 수 있는 접근 로그는 유지됩니다.',
    resultLabel: '공개 드라이브 링크 회수',
    run: revokePrivacyPublicDownloadLinks,
  },
  {
    key: 'travel-media',
    label: '여행 공개 미디어 회수',
    warning: '기존 공개 여행 페이지와 미디어 토큰 접근이 중지됩니다.',
    resultLabel: '여행 공개 미디어 회수',
    run: revokePrivacyTravelPublicMediaShares,
  },
  {
    key: 'photo-location',
    label: '사진 위치정보 제거',
    warning: '일부 사진은 사진 지도나 위치 기반 클러스터에서 사라질 수 있습니다.',
    resultLabel: '사진 위치정보 제거',
    run: removePrivacyPhotoLocationMetadata,
  },
  {
    key: 'cleanup',
    label: '민감 파생 데이터 정리',
    warning: '여러 개인정보 정리 작업이 동시에 실행됩니다. 필요한 데이터는 먼저 내려받아 주세요.',
    resultLabel: '민감 파생 데이터 정리',
    run: cleanupPrivacySensitiveData,
  },
]

const pageCount = computed(() => Math.max(state.pageInfo.totalPages || 0, 1))
const securityModeLabel = computed(() => (security.mode === 'password' ? '비밀번호' : '2차 PIN'))
const securitySaveLabel = computed(() => (security.mode === 'password' ? '비밀번호 변경' : '2차 PIN 변경'))
const isPrivacyBusy = computed(() => Boolean(privacy.busyAction || privacy.exporting || privacy.verifying))
const privacyResultRows = computed(() => {
  if (!privacy.lastResult || typeof privacy.lastResult !== 'object') {
    return []
  }

  return Object.entries(privacy.lastResult)
    .filter(([, value]) => typeof value === 'number')
    .map(([key, value]) => ({ key, label: privacyResultLabel(key), value }))
})

function formatDateTime(value) {
  if (!value) {
    return '-'
  }

  const normalized = new Date(value)
  if (Number.isNaN(normalized.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(normalized)
}

function formatNumber(value) {
  return new Intl.NumberFormat('ko-KR').format(Number(value) || 0)
}

function privacyResultLabel(key) {
  const labels = {
    aiAnalysisHistoriesDeleted: '삭제된 AI 분석 이력',
    publicDownloadLinksRevoked: '회수된 공개 드라이브 링크',
    travelPublicMediaSharesRevoked: '회수된 여행 공개 미디어',
    photoLocationMetadataRemoved: '제거된 사진 위치정보',
  }
  return labels[key] || key
}

function resetForm() {
  form.title = ''
  form.content = ''
  form.attachment = null
  if (attachmentInput.value) {
    attachmentInput.value.value = ''
  }
}

function resetSecurityState() {
  security.gateVisible = false
  security.changeVisible = false
  security.verifying = false
  security.saving = false
  security.errorMessage = ''
  security.secondaryPin = ''
  security.verifiedSecondaryPin = ''
  security.newPassword = ''
  security.confirmPassword = ''
  security.newSecondaryPin = ''
  security.confirmSecondaryPin = ''
}

function resetPrivacyMessages() {
  privacy.errorMessage = ''
  privacy.successMessage = ''
}

function resetPrivacyAccess() {
  privacy.verified = false
  privacy.verifying = false
  privacy.accessPassword = ''
  privacy.accessSecondaryPin = ''
}

function openPrivacyManagementModal() {
  resetPrivacyMessages()
  resetPrivacyAccess()
  privacy.lastActionLabel = ''
  privacy.lastResult = null
  privacy.modalVisible = true
}

function closePrivacyManagementModal() {
  if (isPrivacyBusy.value) {
    return
  }
  privacy.modalVisible = false
  resetPrivacyAccess()
}

function openSecurityGate(mode) {
  resetSecurityState()
  security.mode = mode
  security.gateVisible = true
}

function closeSecurityModal() {
  resetSecurityState()
}

function openPrivacyExportGate() {
  resetPrivacyMessages()
  privacy.secondaryPin = ''
  privacy.exportGateVisible = true
}

function closePrivacyExportGate() {
  if (privacy.exporting) {
    return
  }
  privacy.exportGateVisible = false
  privacy.secondaryPin = ''
}

function handleAttachmentChange(event) {
  form.attachment = event.target.files?.[0] ?? null
}

function selectProfileSection(section) {
  activeProfileSection.value = section
}

async function selectSupportSection(section) {
  activeSupportSection.value = section
  if (section === 'history' && !state.historyLoaded && !state.loading) {
    await loadInquiries(0)
  }
}

function toggleInquiry(inquiryId) {
  state.expandedInquiryId = state.expandedInquiryId === inquiryId ? null : inquiryId
}

function syncExpandedInquiry(preferredId = state.expandedInquiryId) {
  if (!state.inquiries.length) {
    state.expandedInquiryId = null
    return
  }

  const nextInquiry = state.inquiries.find((item) => item.id === preferredId) ?? state.inquiries[0]
  state.expandedInquiryId = nextInquiry?.id ?? null
}

async function loadInquiries(page = state.pageInfo.page, preferredId = state.expandedInquiryId) {
  state.loading = true
  state.errorMessage = ''

  try {
    const response = await fetchMySupportInquiries(page, PAGE_SIZE)
    state.inquiries = response.content ?? []
    state.pageInfo = {
      page: response.page ?? page,
      size: response.size ?? PAGE_SIZE,
      totalElements: response.totalElements ?? 0,
      totalPages: response.totalPages ?? 0,
    }

    if ((response.totalPages ?? 0) > 0 && page >= response.totalPages) {
      await loadInquiries(response.totalPages - 1, preferredId)
      return
    }

    syncExpandedInquiry(preferredId)
    state.historyLoaded = true
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.loading = false
  }
}

async function changePage(nextPage) {
  if (nextPage < 0 || nextPage >= pageCount.value || nextPage === state.pageInfo.page) {
    return
  }
  await loadInquiries(nextPage)
}

async function handleSubmitInquiry() {
  state.sending = true
  state.errorMessage = ''
  state.successMessage = ''

  try {
    const formData = new FormData()
    formData.append('title', form.title)
    formData.append('content', form.content)
    if (form.attachment) {
      formData.append('attachment', form.attachment)
    }

    const createdInquiry = await createSupportInquiry(formData)
    state.successMessage = '문의가 관리자에게 전송되었습니다.'
    resetForm()
    activeProfileSection.value = 'support'
    activeSupportSection.value = 'history'
    await loadInquiries(0, createdInquiry.id)
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.sending = false
  }
}

async function handleVerifySecondaryPin() {
  security.verifying = true
  security.errorMessage = ''

  try {
    await verifyProfileSecondaryPin(security.secondaryPin)
    security.verifiedSecondaryPin = security.secondaryPin.trim()
    security.secondaryPin = ''
    security.gateVisible = false
    security.changeVisible = true
  } catch (error) {
    security.errorMessage = error.message
  } finally {
    security.verifying = false
  }
}

async function handleCredentialChange() {
  security.saving = true
  security.errorMessage = ''

  try {
    if (security.mode === 'password') {
      if (security.newPassword.trim().length < 8) {
        throw new Error('비밀번호는 8자 이상이어야 합니다.')
      }
      if (security.newPassword !== security.confirmPassword) {
        throw new Error('비밀번호 확인이 일치하지 않습니다.')
      }

      await changeProfilePassword({
        secondaryPin: security.verifiedSecondaryPin,
        newPassword: security.newPassword,
      })
      state.successMessage = '비밀번호가 변경되었습니다.'
    } else {
      if (!/^\d{8}$/.test(security.newSecondaryPin.trim())) {
        throw new Error('2차 PIN은 숫자 8자리여야 합니다.')
      }
      if (security.newSecondaryPin !== security.confirmSecondaryPin) {
        throw new Error('2차 PIN 확인이 일치하지 않습니다.')
      }

      await changeProfileSecondaryPin({
        secondaryPin: security.verifiedSecondaryPin,
        newSecondaryPin: security.newSecondaryPin,
      })
      state.successMessage = '2차 PIN이 변경되었습니다.'
    }

    closeSecurityModal()
  } catch (error) {
    security.errorMessage = error.message
  } finally {
    security.saving = false
  }
}

async function handlePrivacyAccessVerify() {
  if (privacy.verifying) {
    return
  }

  resetPrivacyMessages()

  try {
    if (!privacy.accessPassword.trim()) {
      throw new Error('현재 로그인 비밀번호를 입력해 주세요.')
    }
    if (!/^\d{8}$/.test(privacy.accessSecondaryPin.trim())) {
      throw new Error('2차 PIN은 숫자 8자리여야 합니다.')
    }

    privacy.verifying = true
    await verifyProfilePrivacyAccess({
      password: privacy.accessPassword,
      secondaryPin: privacy.accessSecondaryPin,
    })
    privacy.verified = true
    privacy.successMessage = '검증이 완료되었습니다. 개인정보 관리 작업을 실행할 수 있습니다.'
  } catch (error) {
    privacy.errorMessage = error.message
  } finally {
    privacy.verifying = false
    privacy.accessPassword = ''
    privacy.accessSecondaryPin = ''
  }
}

async function runPrivacyAction(action) {
  if (isPrivacyBusy.value) {
    return
  }
  if (!privacy.verified) {
    privacy.modalVisible = true
    privacy.errorMessage = '개인정보 관리 작업을 실행하려면 현재 비밀번호와 2차 PIN 검증이 필요합니다.'
    return
  }

  const confirmed = window.confirm(`${action.label}\n\n${action.warning}\n\n계속 진행할까요?`)
  if (!confirmed) {
    return
  }

  resetPrivacyMessages()
  privacy.busyAction = action.key
  privacy.lastActionLabel = action.resultLabel
  privacy.lastResult = null

  try {
    privacy.lastResult = await action.run()
    privacy.successMessage = `${action.resultLabel} 작업이 완료되었습니다.`
  } catch (error) {
    privacy.errorMessage = error.message
  } finally {
    privacy.busyAction = ''
  }
}

async function handlePrivacyExport() {
  if (privacy.exporting) {
    return
  }

  resetPrivacyMessages()

  try {
    if (privacy.exportFrom && privacy.exportTo && privacy.exportFrom > privacy.exportTo) {
      throw new Error('내보내기 시작일은 종료일보다 늦을 수 없습니다.')
    }
    if (!privacy.secondaryPin.trim()) {
      throw new Error('데이터를 내보내기 전에 2차 PIN을 입력해 주세요.')
    }

    privacy.exporting = true
    await verifyProfileSecondaryPin(privacy.secondaryPin)
    await downloadPrivacyDataExport({
      from: privacy.exportFrom || undefined,
      to: privacy.exportTo || undefined,
    })
    privacy.successMessage = '암호화된 데이터 압축 파일 다운로드를 시작했습니다.'
    privacy.exportGateVisible = false
    privacy.secondaryPin = ''
  } catch (error) {
    privacy.errorMessage = error.message
  } finally {
    privacy.exporting = false
  }
}

</script>

<template>
  <section :class="['workspace-stack', 'profile-workspace', { 'profile-workspace--embedded': embedded }]">
    <section class="panel profile-section-navigation">
      <div class="panel__header profile-section-navigation__header">
        <h2 v-if="!embedded">프로필</h2>
        <nav class="profile-section-tabs" role="tablist" aria-label="프로필 기능">
          <button
            class="button button--ghost"
            :class="{ 'is-active': activeProfileSection === 'account' }"
            type="button"
            role="tab"
            :aria-selected="activeProfileSection === 'account'"
            @click="selectProfileSection('account')"
          >
            계정 및 보안
          </button>
          <button
            class="button button--ghost"
            :class="{ 'is-active': activeProfileSection === 'privacy' }"
            type="button"
            role="tab"
            :aria-selected="activeProfileSection === 'privacy'"
            @click="selectProfileSection('privacy')"
          >
            개인정보 관리
          </button>
          <button
            class="button button--ghost"
            :class="{ 'is-active': activeProfileSection === 'support' }"
            type="button"
            role="tab"
            :aria-selected="activeProfileSection === 'support'"
            @click="selectProfileSection('support')"
          >
            고객 지원
          </button>
        </nav>
      </div>
    </section>

    <div v-if="state.successMessage" class="feedback feedback--success">{{ state.successMessage }}</div>
    <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>

    <section v-if="activeProfileSection === 'account'" class="panel profile-section-panel">
      <div class="panel__header">
        <h2>계정 정보</h2>
      </div>
      <div class="summary-grid profile-summary-grid">
        <article class="summary-card">
          <span>로그인 ID</span>
          <strong>{{ currentUser.loginId }}</strong>
        </article>
        <article class="summary-card">
          <span>표시 이름</span>
          <strong>{{ currentUser.displayName }}</strong>
        </article>
        <article class="summary-card">
          <span>권한</span>
          <strong>{{ currentUser.admin ? '관리자' : '사용자' }}</strong>
        </article>
      </div>
      <div class="profile-security-actions">
        <button class="button button--ghost" type="button" @click="openSecurityGate('password')">비밀번호 변경</button>
        <button class="button button--ghost" type="button" @click="openSecurityGate('secondary-pin')">2차 PIN 변경</button>
      </div>
    </section>

    <section v-if="activeProfileSection === 'privacy'" class="panel profile-privacy-panel profile-section-panel" aria-labelledby="privacy-panel-title">
      <div class="panel__header">
        <h2 id="privacy-panel-title">개인정보 관리</h2>
        <div class="profile-privacy-panel__actions">
          <span class="panel__badge">로그인 + 2차 PIN 보호</span>
          <button class="button button--primary" data-testid="privacy-management-open" type="button" :disabled="isPrivacyBusy" @click="openPrivacyManagementModal">
            개인정보 관리
          </button>
        </div>
      </div>

      <div class="privacy-export-card" data-testid="privacy-data-export-card">
        <div>
          <strong>내 데이터 다운로드</strong>
          <p>가계부 CSV와 내보내기 메타데이터를 2차 PIN으로 암호화된 ZIP 파일로 다운로드합니다.</p>
          <small>현재 압축 파일에는 가계부 CSV와 안전한 목록 정보만 포함됩니다.</small>
        </div>
        <div class="privacy-export-card__range" aria-label="내보내기 날짜 범위">
          <label class="field">
            <span class="field__label">시작일</span>
            <input v-model="privacy.exportFrom" data-testid="privacy-export-from" type="date" :disabled="isPrivacyBusy" />
          </label>
          <label class="field">
            <span class="field__label">종료일</span>
            <input v-model="privacy.exportTo" data-testid="privacy-export-to" type="date" :disabled="isPrivacyBusy" />
          </label>
        </div>
        <button class="button button--primary" data-testid="privacy-export-open" type="button" :disabled="isPrivacyBusy" @click="openPrivacyExportGate">
          {{ privacy.exporting ? '내보내는 중...' : '데이터 내보내기' }}
        </button>
      </div>

      <div class="privacy-management-card" data-testid="privacy-management-card">
        <strong>민감 데이터 정리 및 접근 제어</strong>
        <button class="button button--ghost" type="button" :disabled="isPrivacyBusy" @click="openPrivacyManagementModal">관리 열기</button>
      </div>
    </section>

    <section v-if="activeProfileSection === 'support'" class="profile-support-area" aria-label="고객 지원">
      <nav class="profile-support-tabs" role="tablist" aria-label="문의 기능">
        <button
          class="button button--ghost"
          :class="{ 'is-active': activeSupportSection === 'create' }"
          type="button"
          role="tab"
          :aria-selected="activeSupportSection === 'create'"
          @click="selectSupportSection('create')"
        >
          문의 보내기
        </button>
        <button
          class="button button--ghost"
          :class="{ 'is-active': activeSupportSection === 'history' }"
          type="button"
          role="tab"
          :aria-selected="activeSupportSection === 'history'"
          @click="selectSupportSection('history')"
        >
          내 문의 내역
        </button>
      </nav>

      <section v-if="activeSupportSection === 'create'" class="panel profile-section-panel">
        <div class="panel__header">
          <h2>문의 보내기</h2>
        </div>

        <form class="stack-form" @submit.prevent="handleSubmitInquiry">
          <input v-model="form.title" type="text" maxlength="140" placeholder="문의 제목" :disabled="state.sending" />
          <textarea v-model="form.content" rows="6" placeholder="문제 상황이나 요청 내용을 입력해 주세요." :disabled="state.sending" />
          <input ref="attachmentInput" type="file" accept="image/*" :disabled="state.sending" @change="handleAttachmentChange" />
          <p class="field__hint">첨부 파일은 선택 사항입니다.</p>
          <button class="button button--primary" type="submit" :disabled="state.sending">
            {{ state.sending ? '전송 중...' : '문의 보내기' }}
          </button>
        </form>
      </section>

      <section v-else class="panel profile-section-panel">
        <div class="panel__header">
          <h2>내 문의 내역</h2>
          <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadInquiries(state.pageInfo.page)">
            {{ state.loading ? '불러오는 중...' : '새로고침' }}
          </button>
        </div>

        <p v-if="state.loading" class="panel__empty">문의 내역을 불러오는 중입니다.</p>
        <div v-else-if="state.inquiries.length" class="support-inquiry-list support-inquiry-list--compact profile-inquiry-list">
          <article v-for="inquiry in state.inquiries" :key="inquiry.id" class="support-inquiry-card support-inquiry-card--compact profile-inquiry-card">
            <button class="support-inquiry-row" type="button" @click="toggleInquiry(inquiry.id)">
              <div class="support-inquiry-row__summary">
                <strong class="support-inquiry-row__title">{{ inquiry.title }}</strong>
                <span :class="['entry-type-pill', inquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                  {{ statusLabel[inquiry.status] ?? inquiry.status }}
                </span>
              </div>
              <div class="support-inquiry-row__meta">
                <small>{{ formatDateTime(inquiry.createdAt) }}</small>
                <span class="support-inquiry-row__toggle">{{ state.expandedInquiryId === inquiry.id ? '접기' : '열기' }}</span>
              </div>
            </button>

            <div v-if="state.expandedInquiryId === inquiry.id" class="support-inquiry-row__body profile-inquiry-body">
              <p class="support-inquiry-content">{{ inquiry.content }}</p>

              <div v-if="inquiry.attachmentUrl" class="support-inquiry-attachment profile-inquiry-attachment">
                <img
                  v-if="inquiry.attachmentContentType?.startsWith('image/')"
                  :src="buildThumbnailUrl(inquiry.attachmentUrl)"
                  :alt="inquiry.attachmentFileName || inquiry.title"
                  loading="lazy"
                  decoding="async"
                  class="support-inquiry-preview profile-inquiry-preview"
                />
                <div class="profile-inquiry-attachment__meta">
                  <strong>{{ inquiry.attachmentFileName || '첨부 파일' }}</strong>
                  <a class="button button--ghost profile-inquiry-attachment__button" :href="inquiry.attachmentUrl" target="_blank" rel="noreferrer">첨부 파일 보기</a>
                </div>
              </div>

              <div v-if="inquiry.replyContent" class="support-inquiry-reply profile-inquiry-reply">
                <div class="support-inquiry-reply__header">
                  <strong>관리자 답변</strong>
                  <small>{{ inquiry.repliedByDisplayName || inquiry.repliedByLoginId || '관리자' }} · {{ formatDateTime(inquiry.repliedAt) }}</small>
                </div>
                <p>{{ inquiry.replyContent }}</p>
              </div>
            </div>
          </article>
        </div>
        <p v-else class="panel__empty">아직 등록한 문의가 없습니다.</p>

        <div v-if="!state.loading && state.pageInfo.totalElements > 0" class="panel__actions">
          <button class="button button--ghost" type="button" :disabled="state.pageInfo.page <= 0" @click="changePage(state.pageInfo.page - 1)">이전</button>
          <span>{{ state.pageInfo.page + 1 }} / {{ pageCount }}</span>
          <button class="button button--ghost" type="button" :disabled="state.pageInfo.page + 1 >= pageCount" @click="changePage(state.pageInfo.page + 1)">다음</button>
        </div>
      </section>
    </section>
    <div v-if="privacy.modalVisible" class="travel-modal" @keydown.esc="closePrivacyManagementModal">
      <div class="travel-modal__dialog profile-privacy-modal" data-testid="privacy-management-dialog" role="dialog" aria-modal="true" aria-labelledby="privacy-management-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="privacy-management-title">개인정보 관리</h2>
          </div>
          <button class="button button--ghost" type="button" :disabled="isPrivacyBusy" @click="closePrivacyManagementModal">닫기</button>
        </div>

        <div class="travel-modal__body profile-privacy-modal__body">
          <div v-if="privacy.successMessage" class="feedback feedback--success" data-testid="privacy-success-message" aria-live="polite">{{ privacy.successMessage }}</div>
          <div v-if="privacy.errorMessage" class="feedback feedback--error" data-testid="privacy-error-message" aria-live="assertive">{{ privacy.errorMessage }}</div>

          <form v-if="!privacy.verified" class="profile-privacy-verification" data-testid="privacy-access-form" @submit.prevent="handlePrivacyAccessVerify">
            <div>
              <strong>검증 필요</strong>
            </div>
            <div class="profile-privacy-verification__grid">
              <label class="field">
                <span class="field__label">현재 로그인 비밀번호</span>
                <input v-model="privacy.accessPassword" type="password" autocomplete="current-password" :disabled="privacy.verifying" placeholder="현재 비밀번호" />
              </label>
              <label class="field">
                <span class="field__label">현재 2차 PIN</span>
                <input v-model="privacy.accessSecondaryPin" type="password" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*" maxlength="8" :disabled="privacy.verifying" placeholder="숫자 8자리" />
              </label>
            </div>
            <div class="profile-privacy-verification__actions">
              <button class="button button--primary" type="submit" :disabled="privacy.verifying">
                {{ privacy.verifying ? '검증 중...' : '검증 후 작업 열기' }}
              </button>
            </div>
          </form>

          <template v-else>
            <div class="profile-privacy-unlocked" data-testid="privacy-access-unlocked">
              <strong>검증 완료</strong>
            </div>

            <div class="privacy-action-grid profile-privacy-modal__actions">
              <article v-for="action in privacyActions" :key="action.key" class="privacy-action-card" :data-testid="`privacy-action-${action.key}`">
                <div>
                  <h3>{{ action.label }}</h3>
                  <small>{{ action.warning }}</small>
                </div>
                <button
                  class="button button--ghost privacy-action-card__button"
                  :data-testid="`privacy-action-run-${action.key}`"
                  type="button"
                  :disabled="isPrivacyBusy"
                  @click="runPrivacyAction(action)"
                >
                  {{ privacy.busyAction === action.key ? '처리 중...' : '실행' }}
                </button>
              </article>
            </div>

            <div v-if="privacyResultRows.length" class="privacy-result-list" data-testid="privacy-action-result" aria-live="polite">
              <strong>{{ privacy.lastActionLabel }} 결과</strong>
              <dl>
                <template v-for="row in privacyResultRows" :key="row.key">
                  <dt>{{ row.label }}</dt>
                  <dd>{{ formatNumber(row.value) }}</dd>
                </template>
              </dl>
            </div>
          </template>
        </div>
      </div>
    </div>
    <div v-if="privacy.exportGateVisible" class="travel-modal" @keydown.esc="closePrivacyExportGate">
      <div class="travel-modal__dialog profile-security-modal" data-testid="privacy-export-dialog" role="dialog" aria-modal="true" aria-labelledby="privacy-export-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="privacy-export-title">데이터 내보내기 확인</h2>
            <p>압축 파일에는 민감한 금융 데이터가 포함될 수 있습니다. 먼저 현재 2차 PIN을 확인합니다.</p>
          </div>
          <button class="button button--ghost" type="button" :disabled="privacy.exporting" @click="closePrivacyExportGate">닫기</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="privacy.errorMessage" class="feedback feedback--error" data-testid="privacy-export-error-message" aria-live="assertive">{{ privacy.errorMessage }}</div>
          <label class="field">
            <span class="field__label">현재 2차 PIN</span>
            <input
              v-model="privacy.secondaryPin"
              data-testid="privacy-export-secondary-pin"
              type="password"
              inputmode="numeric"
              autocomplete="one-time-code"
              pattern="[0-9]*"
              maxlength="8"
              placeholder="숫자 8자리"
              :disabled="privacy.exporting"
              @keyup.enter="handlePrivacyExport"
            />
          </label>
          <p class="field__hint">다운로드되는 ZIP 파일은 확인된 2차 PIN으로 암호화됩니다.</p>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="privacy.exporting" @click="closePrivacyExportGate">취소</button>
          <button class="button button--primary" type="button" :disabled="privacy.exporting" @click="handlePrivacyExport">
            {{ privacy.exporting ? '확인 및 생성 중...' : '확인 후 다운로드' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="security.gateVisible" class="travel-modal" @keydown.esc="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal" role="dialog" aria-modal="true" aria-labelledby="security-gate-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="security-gate-title">{{ securityModeLabel }} 변경 잠금 해제</h2>
            <p>민감한 계정 정보를 변경하려면 현재 2차 PIN을 먼저 입력해야 합니다.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">닫기</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>
          <label class="field">
            <span class="field__label">현재 2차 PIN</span>
            <input v-model="security.secondaryPin" type="password" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*" maxlength="8" placeholder="숫자 8자리" :disabled="security.verifying" />
          </label>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="security.verifying" @click="closeSecurityModal">취소</button>
          <button class="button button--primary" type="button" :disabled="security.verifying" @click="handleVerifySecondaryPin">
            {{ security.verifying ? '확인 중...' : '확인' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="security.changeVisible" class="travel-modal" @keydown.esc="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal" role="dialog" aria-modal="true" aria-labelledby="security-change-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="security-change-title">{{ securitySaveLabel }}</h2>
            <p>{{ security.mode === 'password' ? '새 비밀번호는 즉시 적용됩니다.' : '새 2차 PIN은 숫자 8자리여야 합니다.' }}</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">닫기</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>

          <template v-if="security.mode === 'password'">
            <label class="field">
              <span class="field__label">새 비밀번호</span>
              <input v-model="security.newPassword" type="password" placeholder="8자 이상" :disabled="security.saving" />
            </label>
            <label class="field">
              <span class="field__label">새 비밀번호 확인</span>
              <input v-model="security.confirmPassword" type="password" placeholder="다시 입력" :disabled="security.saving" />
            </label>
          </template>

          <template v-else>
            <label class="field">
              <span class="field__label">새 2차 PIN</span>
              <input v-model="security.newSecondaryPin" type="password" inputmode="numeric" autocomplete="new-password" pattern="[0-9]*" maxlength="8" placeholder="숫자 8자리" :disabled="security.saving" />
            </label>
            <label class="field">
              <span class="field__label">새 2차 PIN 확인</span>
              <input v-model="security.confirmSecondaryPin" type="password" inputmode="numeric" autocomplete="new-password" pattern="[0-9]*" maxlength="8" placeholder="다시 입력" :disabled="security.saving" />
            </label>
          </template>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="security.saving" @click="closeSecurityModal">취소</button>
          <button class="button button--primary" type="button" :disabled="security.saving" @click="handleCredentialChange">
            {{ security.saving ? '저장 중...' : securitySaveLabel }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import {
  changeProfilePassword,
  changeProfileSecondaryPin,
  createSupportInquiry,
  fetchMySupportInquiries,
  verifyProfileSecondaryPin,
} from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
})

const PAGE_SIZE = 5
const attachmentInput = ref(null)

const state = reactive({
  loading: true,
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

const statusLabel = {
  PENDING: '답변 대기',
  ANSWERED: '답변 완료',
}

const pageCount = computed(() => Math.max(state.pageInfo.totalPages || 0, 1))
const securityModeLabel = computed(() => (security.mode === 'password' ? '비밀번호' : '2차 비밀번호'))
const securitySaveLabel = computed(() => (security.mode === 'password' ? '비밀번호 변경' : '2차 비밀번호 변경'))

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

function openSecurityGate(mode) {
  resetSecurityState()
  security.mode = mode
  security.gateVisible = true
}

function closeSecurityModal() {
  resetSecurityState()
}

function handleAttachmentChange(event) {
  form.attachment = event.target.files?.[0] ?? null
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
    state.successMessage = '문의가 관리자 메일함으로 전송되었습니다.'
    resetForm()
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
        throw new Error('새 비밀번호 확인이 일치하지 않습니다.')
      }

      await changeProfilePassword({
        secondaryPin: security.verifiedSecondaryPin,
        newPassword: security.newPassword,
      })
      state.successMessage = '비밀번호를 변경했습니다.'
    } else {
      if (!/^\d{8}$/.test(security.newSecondaryPin.trim())) {
        throw new Error('2차 비밀번호는 숫자 8자리여야 합니다.')
      }
      if (security.newSecondaryPin !== security.confirmSecondaryPin) {
        throw new Error('새 2차 비밀번호 확인이 일치하지 않습니다.')
      }

      await changeProfileSecondaryPin({
        secondaryPin: security.verifiedSecondaryPin,
        newSecondaryPin: security.newSecondaryPin,
      })
      state.successMessage = '2차 비밀번호를 변경했습니다.'
    }

    closeSecurityModal()
  } catch (error) {
    security.errorMessage = error.message
  } finally {
    security.saving = false
  }
}

onMounted(() => {
  loadInquiries(0)
})
</script>

<template>
  <section class="workspace-stack profile-workspace">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>내 프로필</h2>
          <p>계정 정보와 내가 보낸 문의, 관리자 답변을 이곳에서 확인합니다.</p>
        </div>
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
          <span>권한 표시</span>
          <strong>{{ currentUser.admin ? '관리자' : '일반 사용자' }}</strong>
        </article>
      </div>
      <div class="profile-security-actions">
        <button class="button button--ghost" type="button" @click="openSecurityGate('password')">비밀번호 변경</button>
        <button class="button button--ghost" type="button" @click="openSecurityGate('secondary-pin')">2차 비밀번호 변경</button>
      </div>
    </section>

    <div v-if="state.successMessage" class="feedback feedback--success">{{ state.successMessage }}</div>
    <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>요청사항 보내기</h2>
          <p>제목, 내용, 첨부 이미지를 담아 관리자에게 바로 전달할 수 있습니다.</p>
        </div>
      </div>

      <form class="stack-form" @submit.prevent="handleSubmitInquiry">
        <input
          v-model="form.title"
          type="text"
          maxlength="140"
          placeholder="문의 제목"
          :disabled="state.sending"
        />
        <textarea
          v-model="form.content"
          rows="6"
          placeholder="문의 내용이나 건의 사항을 입력해 주세요."
          :disabled="state.sending"
        />
        <input
          ref="attachmentInput"
          type="file"
          accept="image/*"
          :disabled="state.sending"
          @change="handleAttachmentChange"
        />
        <p class="field__hint">첨부 이미지는 선택 사항이며, 관리자와 본인만 볼 수 있습니다.</p>
        <button class="button button--primary" type="submit" :disabled="state.sending">
          {{ state.sending ? '전송 중...' : '문의 보내기' }}
        </button>
      </form>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>내 문의 내역</h2>
          <p>문의는 한 줄 목록으로 보고, 필요한 항목만 열어서 상세 내용과 답변을 확인할 수 있습니다.</p>
        </div>
        <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadInquiries(state.pageInfo.page)">
          {{ state.loading ? '불러오는 중...' : '새로고침' }}
        </button>
      </div>

      <p v-if="state.loading" class="panel__empty">문의 내역을 불러오는 중입니다.</p>
      <div v-else-if="state.inquiries.length" class="support-inquiry-list support-inquiry-list--compact">
        <article
          v-for="inquiry in state.inquiries"
          :key="inquiry.id"
          class="support-inquiry-card support-inquiry-card--compact"
        >
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

          <div v-if="state.expandedInquiryId === inquiry.id" class="support-inquiry-row__body">
            <p class="support-inquiry-content">{{ inquiry.content }}</p>

            <div v-if="inquiry.attachmentUrl" class="support-inquiry-attachment">
              <a class="button button--ghost" :href="inquiry.attachmentUrl" target="_blank" rel="noreferrer">
                첨부 이미지 보기
              </a>
              <img
                v-if="inquiry.attachmentContentType?.startsWith('image/')"
                :src="buildThumbnailUrl(inquiry.attachmentUrl)"
                :alt="inquiry.attachmentFileName || inquiry.title"
                loading="lazy"
                decoding="async"
                class="support-inquiry-preview"
              />
            </div>

            <div v-if="inquiry.replyContent" class="support-inquiry-reply">
              <div class="support-inquiry-reply__header">
                <strong>관리자 답변</strong>
                <small>{{ inquiry.repliedByDisplayName || inquiry.repliedByLoginId || '관리자' }} · {{ formatDateTime(inquiry.repliedAt) }}</small>
              </div>
              <p>{{ inquiry.replyContent }}</p>
            </div>
          </div>
        </article>
      </div>
      <p v-else class="panel__empty">아직 보낸 문의가 없습니다.</p>

      <div v-if="!state.loading && state.pageInfo.totalElements > 0" class="panel__actions">
        <button class="button button--ghost" type="button" :disabled="state.pageInfo.page <= 0" @click="changePage(state.pageInfo.page - 1)">
          이전
        </button>
        <span>{{ state.pageInfo.page + 1 }} / {{ pageCount }}</span>
        <button
          class="button button--ghost"
          type="button"
          :disabled="state.pageInfo.page + 1 >= pageCount"
          @click="changePage(state.pageInfo.page + 1)"
        >
          다음
        </button>
      </div>
    </section>

    <div v-if="security.gateVisible" class="travel-modal" @click.self="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal">
        <div class="travel-modal__header">
          <div>
            <h2>{{ securityModeLabel }} 열기</h2>
            <p>{{ securityModeLabel }} 변경 모달을 열려면 현재 2차 비밀번호를 먼저 입력해 주세요.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">닫기</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>
          <label class="field">
            <span class="field__label">현재 2차 비밀번호</span>
            <input
              v-model="security.secondaryPin"
              type="password"
              inputmode="numeric"
              maxlength="8"
              placeholder="숫자 8자리"
              :disabled="security.verifying"
            />
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

    <div v-if="security.changeVisible" class="travel-modal" @click.self="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal">
        <div class="travel-modal__header">
          <div>
            <h2>{{ securitySaveLabel }}</h2>
            <p>{{ security.mode === 'password'
              ? '새 비밀번호를 입력하면 즉시 계정에 적용됩니다.'
              : '새 2차 비밀번호는 숫자 8자리여야 합니다.' }}</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">닫기</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>

          <template v-if="security.mode === 'password'">
            <label class="field">
              <span class="field__label">새 비밀번호</span>
              <input
                v-model="security.newPassword"
                type="password"
                placeholder="8자 이상"
                :disabled="security.saving"
              />
            </label>
            <label class="field">
              <span class="field__label">새 비밀번호 확인</span>
              <input
                v-model="security.confirmPassword"
                type="password"
                placeholder="한 번 더 입력"
                :disabled="security.saving"
              />
            </label>
          </template>

          <template v-else>
            <label class="field">
              <span class="field__label">새 2차 비밀번호</span>
              <input
                v-model="security.newSecondaryPin"
                type="password"
                inputmode="numeric"
                maxlength="8"
                placeholder="숫자 8자리"
                :disabled="security.saving"
              />
            </label>
            <label class="field">
              <span class="field__label">새 2차 비밀번호 확인</span>
              <input
                v-model="security.confirmSecondaryPin"
                type="password"
                inputmode="numeric"
                maxlength="8"
                placeholder="한 번 더 입력"
                :disabled="security.saving"
              />
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

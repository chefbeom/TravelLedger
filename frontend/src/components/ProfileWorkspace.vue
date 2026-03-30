<script setup>
import { onMounted, reactive, ref } from 'vue'
import { createSupportInquiry, fetchMySupportInquiries } from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
})

const attachmentInput = ref(null)

const state = reactive({
  loading: true,
  sending: false,
  errorMessage: '',
  successMessage: '',
  inquiries: [],
})

const form = reactive({
  title: '',
  content: '',
  attachment: null,
})

const statusLabel = {
  PENDING: '답변 대기',
  ANSWERED: '답변 완료',
}

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

function handleAttachmentChange(event) {
  form.attachment = event.target.files?.[0] ?? null
}

async function loadInquiries() {
  state.loading = true
  state.errorMessage = ''

  try {
    state.inquiries = await fetchMySupportInquiries()
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.loading = false
  }
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
    state.inquiries = [createdInquiry, ...state.inquiries]
    state.successMessage = '문의가 관리자 메일함으로 전송되었습니다.'
    resetForm()
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.sending = false
  }
}

onMounted(loadInquiries)
</script>

<template>
  <section class="workspace-stack profile-workspace">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>내 프로필</h2>
          <p>계정 정보와 내가 보낸 문의, 관리자 답변을 한 곳에서 확인합니다.</p>
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
          <span>권한</span>
          <strong>{{ currentUser.admin ? '관리자' : '일반 사용자' }}</strong>
        </article>
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
          {{ state.sending ? '전송 중..' : '문의 보내기' }}
        </button>
      </form>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>내 문의 내역</h2>
          <p>보낸 문의와 관리자 답변을 최신순으로 확인할 수 있습니다.</p>
        </div>
        <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadInquiries">
          {{ state.loading ? '불러오는 중..' : '새로고침' }}
        </button>
      </div>

      <p v-if="state.loading" class="panel__empty">문의 내역을 불러오는 중입니다.</p>
      <div v-else-if="state.inquiries.length" class="support-inquiry-list">
        <article v-for="inquiry in state.inquiries" :key="inquiry.id" class="support-inquiry-card">
          <div class="support-inquiry-meta">
            <strong>{{ inquiry.title }}</strong>
            <span :class="['entry-type-pill', inquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
              {{ statusLabel[inquiry.status] ?? inquiry.status }}
            </span>
          </div>
          <small>{{ formatDateTime(inquiry.createdAt) }}</small>
          <p class="support-inquiry-content">{{ inquiry.content }}</p>

          <div v-if="inquiry.attachmentUrl" class="support-inquiry-attachment">
            <a class="button button--ghost" :href="inquiry.attachmentUrl" target="_blank" rel="noreferrer">
              첨부 이미지 보기
            </a>
            <img
              v-if="inquiry.attachmentContentType?.startsWith('image/')"
              :src="inquiry.attachmentUrl"
              :alt="inquiry.attachmentFileName || inquiry.title"
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
        </article>
      </div>
      <p v-else class="panel__empty">아직 보낸 문의가 없습니다.</p>
    </section>
  </section>
</template>

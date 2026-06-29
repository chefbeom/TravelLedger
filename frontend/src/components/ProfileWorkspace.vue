<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import {
  changeProfilePassword,
  changeProfileSecondaryPin,
  cleanupPrivacySensitiveData,
  createSupportInquiry,
  deletePrivacyAiAnalysisHistory,
  downloadPrivacyDataExport,
  fetchMySupportInquiries,
  revokePrivacyPublicDownloadLinks,
  revokePrivacyTravelPublicMediaShares,
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

const privacy = reactive({
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
  PENDING: '?듬? ?湲?,
  ANSWERED: '?듬? ?꾨즺',
}

const privacyActions = [
  {
    key: 'ai-history',
    label: 'AI 遺꾩꽍 ?대젰 ??젣',
    description: '??媛怨꾨? AI 遺꾩꽍 寃곌낵? ??λ맂 遺꾩꽍 ?대젰???곴뎄 ??젣?⑸땲??',
    warning: '??젣 ?꾩뿉??AI 遺꾩꽍 ?대젰??蹂듦뎄?????놁뒿?덈떎.',
    resultLabel: 'AI 遺꾩꽍 ?대젰 ??젣',
    run: deletePrivacyAiAnalysisHistory,
  },
  {
    key: 'drive-links',
    label: '怨듦컻 ?쒕씪?대툕 留곹겕 ?뚯닔',
    description: '?닿? 留뚮뱺 ?쒖꽦 怨듦컻 ?ㅼ슫濡쒕뱶 留곹겕瑜?紐⑤몢 ?먭린?⑸땲??',
    warning: '怨듭쑀諛쏆? ?щ엺? 湲곗〈 怨듦컻 留곹겕濡????댁긽 ?묎렐?????놁뒿?덈떎. ?묎렐 濡쒓렇??蹂댁〈?⑸땲??',
    resultLabel: '怨듦컻 ?쒕씪?대툕 留곹겕 ?뚯닔',
    run: revokePrivacyPublicDownloadLinks,
  },
  {
    key: 'travel-media',
    label: '?ы뻾 怨듦컻 誘몃뵒???뚯닔',
    description: '???ы뻾 怨듦컻 怨듭쑀? 而ㅻ??덊떚 怨듦컻 ?ъ쭊 ?쒕㈃??鍮꾧났媛쒕줈 ?꾪솚?⑸땲??',
    warning: '湲곗〈 ?ы뻾 怨듦컻 ?붾㈃怨?誘몃뵒???좏겙 ?묎렐??以묐떒?⑸땲??',
    resultLabel: '?ы뻾 怨듦컻 誘몃뵒???뚯닔',
    run: revokePrivacyTravelPublicMediaShares,
  },
  {
    key: 'cleanup',
    label: '誘쇨컧 ?뚯깮 ?곗씠???쇨큵 ?뺣━',
    description: 'AI 遺꾩꽍 ?대젰 ??젣, 怨듦컻 ?쒕씪?대툕 留곹겕 ?뚯닔, ?ы뻾 怨듦컻 誘몃뵒???뚯닔瑜???踰덉뿉 ?ㅽ뻾?⑸땲??',
    warning: '?щ윭 媛쒖씤?뺣낫 ?뺣━ ?묒뾽???숈떆???ㅽ뻾?⑸땲?? ?꾩슂???먮즺瑜?癒쇱? ?대젮諛쏆쑝?몄슂.',
    resultLabel: '誘쇨컧 ?뚯깮 ?곗씠???쇨큵 ?뺣━',
    run: cleanupPrivacySensitiveData,
  },
]

const pageCount = computed(() => Math.max(state.pageInfo.totalPages || 0, 1))
const securityModeLabel = computed(() => (security.mode === 'password' ? '鍮꾨?踰덊샇' : '2李?鍮꾨?踰덊샇'))
const securitySaveLabel = computed(() => (security.mode === 'password' ? '鍮꾨?踰덊샇 蹂寃? : '2李?鍮꾨?踰덊샇 蹂寃?))
const isPrivacyBusy = computed(() => Boolean(privacy.busyAction || privacy.exporting))
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
    aiAnalysisHistoriesDeleted: '??젣??AI 遺꾩꽍 ?대젰',
    publicDownloadLinksRevoked: '?뚯닔??怨듦컻 ?쒕씪?대툕 留곹겕',
    travelPublicMediaSharesRevoked: '?뚯닔???ы뻾 怨듦컻 誘몃뵒??怨듭쑀',
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
    state.successMessage = '臾몄쓽媛 愿由ъ옄?먭쾶 ?꾨떖?섏뿀?듬땲??'
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
        throw new Error('鍮꾨?踰덊샇??8???댁긽?댁뼱???⑸땲??')
      }
      if (security.newPassword !== security.confirmPassword) {
        throw new Error('??鍮꾨?踰덊샇? ?뺤씤 媛믪씠 ?쇱튂?섏? ?딆뒿?덈떎.')
      }

      await changeProfilePassword({
        secondaryPin: security.verifiedSecondaryPin,
        newPassword: security.newPassword,
      })
      state.successMessage = '鍮꾨?踰덊샇瑜?蹂寃쏀뻽?듬땲??'
    } else {
      if (!/^\d{8}$/.test(security.newSecondaryPin.trim())) {
        throw new Error('2李?鍮꾨?踰덊샇???レ옄 8?먮━?ъ빞 ?⑸땲??')
      }
      if (security.newSecondaryPin !== security.confirmSecondaryPin) {
        throw new Error('??2李?鍮꾨?踰덊샇? ?뺤씤 媛믪씠 ?쇱튂?섏? ?딆뒿?덈떎.')
      }

      await changeProfileSecondaryPin({
        secondaryPin: security.verifiedSecondaryPin,
        newSecondaryPin: security.newSecondaryPin,
      })
      state.successMessage = '2李?鍮꾨?踰덊샇瑜?蹂寃쏀뻽?듬땲??'
    }

    closeSecurityModal()
  } catch (error) {
    security.errorMessage = error.message
  } finally {
    security.saving = false
  }
}

async function runPrivacyAction(action) {
  if (isPrivacyBusy.value) {
    return
  }

  const confirmed = window.confirm(`${action.label}\n\n${action.warning}\n\n怨꾩냽 吏꾪뻾?좉퉴??`)
  if (!confirmed) {
    return
  }

  resetPrivacyMessages()
  privacy.busyAction = action.key
  privacy.lastActionLabel = action.resultLabel
  privacy.lastResult = null

  try {
    privacy.lastResult = await action.run()
    privacy.successMessage = `${action.resultLabel} ?묒뾽???꾨즺?덉뒿?덈떎.`
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
      throw new Error('?대낫?닿린 ?쒖옉?쇱? 醫낅즺?쇰낫????쓣 ???놁뒿?덈떎.')
    }
    if (!privacy.secondaryPin.trim()) {
      throw new Error('?곗씠???대낫?닿린 ?꾩뿉 2李?鍮꾨?踰덊샇瑜??낅젰??二쇱꽭??')
    }

    privacy.exporting = true
    await verifyProfileSecondaryPin(privacy.secondaryPin)
    await downloadPrivacyDataExport({
      from: privacy.exportFrom || undefined,
      to: privacy.exportTo || undefined,
    })
    privacy.successMessage = '媛쒖씤 ?곗씠???뺤텞 ?뚯씪 ?ㅼ슫濡쒕뱶瑜??쒖옉?덉뒿?덈떎.'
    privacy.exportGateVisible = false
    privacy.secondaryPin = ''
  } catch (error) {
    privacy.errorMessage = error.message
  } finally {
    privacy.exporting = false
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
          <h2>?꾨줈??/h2>
          <p>怨꾩젙 ?뺣낫, 蹂댁븞 ?ㅼ젙, 媛쒖씤?뺣낫 愿由? 愿由ъ옄 臾몄쓽瑜???怨녹뿉???뺤씤?⑸땲??</p>
        </div>
      </div>
      <div class="summary-grid profile-summary-grid">
        <article class="summary-card">
          <span>濡쒓렇??ID</span>
          <strong>{{ currentUser.loginId }}</strong>
        </article>
        <article class="summary-card">
          <span>?쒖떆 ?대쫫</span>
          <strong>{{ currentUser.displayName }}</strong>
        </article>
        <article class="summary-card">
          <span>沅뚰븳</span>
          <strong>{{ currentUser.admin ? '愿由ъ옄' : '?쇰컲 ?ъ슜?? }}</strong>
        </article>
      </div>
      <div class="profile-security-actions">
        <button class="button button--ghost" type="button" @click="openSecurityGate('password')">鍮꾨?踰덊샇 蹂寃?/button>
        <button class="button button--ghost" type="button" @click="openSecurityGate('secondary-pin')">2李?鍮꾨?踰덊샇 蹂寃?/button>
      </div>
    </section>

    <div v-if="state.successMessage" class="feedback feedback--success">{{ state.successMessage }}</div>
    <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>

    <section class="panel profile-privacy-panel" aria-labelledby="privacy-panel-title">
      <div class="panel__header">
        <div>
          <h2 id="privacy-panel-title">媛쒖씤?뺣낫 愿由?/h2>
          <p>AI 遺꾩꽍 ?대젰, 怨듦컻 怨듭쑀 留곹겕, ?ы뻾 怨듦컻 誘몃뵒?? ???곗씠??export瑜?吏곸젒 ?쒖뼱?⑸땲??</p>
        </div>
        <span class="panel__badge">2李?PIN 蹂댄샇</span>
      </div>

      <div class="privacy-export-card">
        <div>
          <strong>???곗씠???ㅼ슫濡쒕뱶</strong>
          <p>媛怨꾨? CSV? export 硫뷀??곗씠?곕? 2李?鍮꾨?踰덊샇濡??뷀샇?붾맂 ZIP?쇰줈 ?대젮諛쏆뒿?덈떎.</p>
        </div>
        <div class="privacy-export-card__range" aria-label="?대낫?닿린 湲곌컙">
          <label class="field">
            <span class="field__label">?쒖옉??/span>
            <input v-model="privacy.exportFrom" type="date" :disabled="isPrivacyBusy" />
          </label>
          <label class="field">
            <span class="field__label">醫낅즺??/span>
            <input v-model="privacy.exportTo" type="date" :disabled="isPrivacyBusy" />
          </label>
        </div>
        <button class="button button--primary" type="button" :disabled="isPrivacyBusy" @click="openPrivacyExportGate">
          {{ privacy.exporting ? '?대낫?대뒗 以?..' : '?곗씠???대낫?닿린' }}
        </button>
      </div>

      <div class="privacy-action-grid">
        <article v-for="action in privacyActions" :key="action.key" class="privacy-action-card">
          <div>
            <h3>{{ action.label }}</h3>
            <p>{{ action.description }}</p>
            <small>{{ action.warning }}</small>
          </div>
          <button
            class="button button--ghost privacy-action-card__button"
            type="button"
            :disabled="isPrivacyBusy"
            @click="runPrivacyAction(action)"
          >
            {{ privacy.busyAction === action.key ? '泥섎━ 以?..' : '?ㅽ뻾' }}
          </button>
        </article>
      </div>

      <div v-if="privacy.successMessage" class="feedback feedback--success" aria-live="polite">{{ privacy.successMessage }}</div>
      <div v-if="privacy.errorMessage" class="feedback feedback--error" aria-live="assertive">{{ privacy.errorMessage }}</div>

      <div v-if="privacyResultRows.length" class="privacy-result-list" aria-live="polite">
        <strong>{{ privacy.lastActionLabel }} 寃곌낵</strong>
        <dl>
          <template v-for="row in privacyResultRows" :key="row.key">
            <dt>{{ row.label }}</dt>
            <dd>{{ formatNumber(row.value) }}嫄?/dd>
          </template>
        </dl>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>臾몄쓽?ы빆 蹂대궡湲?/h2>
          <p>?쒕ぉ, ?댁슜, 泥⑤? ?대?吏瑜??④린硫?愿由ъ옄?먭쾶 ?꾨떖?⑸땲??</p>
        </div>
      </div>

      <form class="stack-form" @submit.prevent="handleSubmitInquiry">
        <input
          v-model="form.title"
          type="text"
          maxlength="140"
          placeholder="臾몄쓽 ?쒕ぉ"
          :disabled="state.sending"
        />
        <textarea
          v-model="form.content"
          rows="6"
          placeholder="臾몄쓽 ?댁슜?대굹 嫄댁쓽 ?ы빆???낅젰??二쇱꽭??"
          :disabled="state.sending"
        />
        <input
          ref="attachmentInput"
          type="file"
          accept="image/*"
          :disabled="state.sending"
          @change="handleAttachmentChange"
        />
        <p class="field__hint">泥⑤? ?대?吏???좏깮 ?ы빆?대ŉ, 愿由ъ옄? 蹂몄씤留?蹂????덉뒿?덈떎.</p>
        <button class="button button--primary" type="submit" :disabled="state.sending">
          {{ state.sending ? '?꾩넚 以?..' : '臾몄쓽 蹂대궡湲? }}
        </button>
      </form>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>??臾몄쓽 ?댁뿭</h2>
          <p>臾몄쓽 吏꾪뻾 ?곹깭? 愿由ъ옄 ?듬????뺤씤?⑸땲??</p>
        </div>
        <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadInquiries(state.pageInfo.page)">
          {{ state.loading ? '遺덈윭?ㅻ뒗 以?..' : '?덈줈怨좎묠' }}
        </button>
      </div>

      <p v-if="state.loading" class="panel__empty">臾몄쓽 ?댁뿭??遺덈윭?ㅻ뒗 以묒엯?덈떎.</p>
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
              <span class="support-inquiry-row__toggle">{{ state.expandedInquiryId === inquiry.id ? '?묎린' : '?닿린' }}</span>
            </div>
          </button>

          <div v-if="state.expandedInquiryId === inquiry.id" class="support-inquiry-row__body">
            <p class="support-inquiry-content">{{ inquiry.content }}</p>

            <div v-if="inquiry.attachmentUrl" class="support-inquiry-attachment">
              <a class="button button--ghost" :href="inquiry.attachmentUrl" target="_blank" rel="noreferrer">
                泥⑤? ?대?吏 蹂닿린
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
                <strong>愿由ъ옄 ?듬?</strong>
                <small>{{ inquiry.repliedByDisplayName || inquiry.repliedByLoginId || '愿由ъ옄' }} 쨌 {{ formatDateTime(inquiry.repliedAt) }}</small>
              </div>
              <p>{{ inquiry.replyContent }}</p>
            </div>
          </div>
        </article>
      </div>
      <p v-else class="panel__empty">?꾩쭅 蹂대궦 臾몄쓽媛 ?놁뒿?덈떎.</p>

      <div v-if="!state.loading && state.pageInfo.totalElements > 0" class="panel__actions">
        <button class="button button--ghost" type="button" :disabled="state.pageInfo.page <= 0" @click="changePage(state.pageInfo.page - 1)">
          ?댁쟾
        </button>
        <span>{{ state.pageInfo.page + 1 }} / {{ pageCount }}</span>
        <button
          class="button button--ghost"
          type="button"
          :disabled="state.pageInfo.page + 1 >= pageCount"
          @click="changePage(state.pageInfo.page + 1)"
        >
          ?ㅼ쓬
        </button>
      </div>
    </section>

    <div v-if="privacy.exportGateVisible" class="travel-modal" @click.self="closePrivacyExportGate">
      <div class="travel-modal__dialog profile-security-modal" role="dialog" aria-modal="true" aria-labelledby="privacy-export-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="privacy-export-title">?곗씠???대낫?닿린 ?뺤씤</h2>
            <p>誘쇨컧??媛쒖씤 湲덉쑖 ?곗씠?곌? ?ы븿?섎?濡??꾩옱 2李?鍮꾨?踰덊샇濡???踰????뺤씤?⑸땲??</p>
          </div>
          <button class="button button--ghost" type="button" :disabled="privacy.exporting" @click="closePrivacyExportGate">?リ린</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="privacy.errorMessage" class="feedback feedback--error">{{ privacy.errorMessage }}</div>
          <label class="field">
            <span class="field__label">?꾩옱 2李?鍮꾨?踰덊샇</span>
            <input
              v-model="privacy.secondaryPin"
              type="password"
              inputmode="numeric"
              autocomplete="one-time-code"
              pattern="[0-9]*"
              maxlength="8"
              placeholder="?レ옄 8?먮━"
              :disabled="privacy.exporting"
              @keyup.enter="handlePrivacyExport"
            />
          </label>
          <p class="field__hint">?ㅼ슫濡쒕뱶 ZIP ?뚯씪? 寃利앸맂 2李?鍮꾨?踰덊샇濡??뷀샇?붾맗?덈떎.</p>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="privacy.exporting" @click="closePrivacyExportGate">痍⑥냼</button>
          <button class="button button--primary" type="button" :disabled="privacy.exporting" @click="handlePrivacyExport">
            {{ privacy.exporting ? '?뺤씤 諛??앹꽦 以?..' : '?뺤씤 ???ㅼ슫濡쒕뱶' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="security.gateVisible" class="travel-modal" @click.self="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal" role="dialog" aria-modal="true" aria-labelledby="security-gate-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="security-gate-title">{{ securityModeLabel }} ?닿린</h2>
            <p>{{ securityModeLabel }} 蹂寃?紐⑤떖???대젮硫??꾩옱 2李?鍮꾨?踰덊샇瑜?癒쇱? ?낅젰??二쇱꽭??</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">?リ린</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>
          <label class="field">
            <span class="field__label">?꾩옱 2李?鍮꾨?踰덊샇</span>
            <input
              v-model="security.secondaryPin"
              type="password"
              inputmode="numeric"
              autocomplete="one-time-code"
              pattern="[0-9]*"
              maxlength="8"
              placeholder="?レ옄 8?먮━"
              :disabled="security.verifying"
            />
          </label>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="security.verifying" @click="closeSecurityModal">痍⑥냼</button>
          <button class="button button--primary" type="button" :disabled="security.verifying" @click="handleVerifySecondaryPin">
            {{ security.verifying ? '?뺤씤 以?..' : '?뺤씤' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="security.changeVisible" class="travel-modal" @click.self="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal" role="dialog" aria-modal="true" aria-labelledby="security-change-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="security-change-title">{{ securitySaveLabel }}</h2>
            <p>{{ security.mode === 'password'
              ? '??鍮꾨?踰덊샇瑜??낅젰?섎㈃ 利됱떆 怨꾩젙???곸슜?⑸땲??'
              : '??2李?鍮꾨?踰덊샇???レ옄 8?먮━?ъ빞 ?⑸땲??' }}</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">?リ린</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>

          <template v-if="security.mode === 'password'">
            <label class="field">
              <span class="field__label">??鍮꾨?踰덊샇</span>
              <input
                v-model="security.newPassword"
                type="password"
                placeholder="8???댁긽"
                :disabled="security.saving"
              />
            </label>
            <label class="field">
              <span class="field__label">??鍮꾨?踰덊샇 ?뺤씤</span>
              <input
                v-model="security.confirmPassword"
                type="password"
                placeholder="??踰????낅젰"
                :disabled="security.saving"
              />
            </label>
          </template>

          <template v-else>
            <label class="field">
              <span class="field__label">??2李?鍮꾨?踰덊샇</span>
              <input
                v-model="security.newSecondaryPin"
                type="password"
                inputmode="numeric"
                autocomplete="new-password"
                pattern="[0-9]*"
                maxlength="8"
                placeholder="?レ옄 8?먮━"
                :disabled="security.saving"
              />
            </label>
            <label class="field">
              <span class="field__label">??2李?鍮꾨?踰덊샇 ?뺤씤</span>
              <input
                v-model="security.confirmSecondaryPin"
                type="password"
                inputmode="numeric"
                autocomplete="new-password"
                pattern="[0-9]*"
                maxlength="8"
                placeholder="??踰????낅젰"
                :disabled="security.saving"
              />
            </label>
          </template>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="security.saving" @click="closeSecurityModal">痍⑥냼</button>
          <button class="button button--primary" type="button" :disabled="security.saving" @click="handleCredentialChange">
            {{ security.saving ? '???以?..' : securitySaveLabel }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
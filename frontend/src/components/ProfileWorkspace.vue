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
  removePrivacyPhotoLocationMetadata,
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
  PENDING: 'Waiting for reply',
  ANSWERED: 'Answered',
}

const privacyActions = [
  {
    key: 'ai-history',
    label: 'Delete AI analysis history',
    description: 'Permanently deletes ledger AI analysis history owned by this account.',
    warning: 'Deleted AI analysis history cannot be restored.',
    resultLabel: 'AI analysis history deletion',
    run: deletePrivacyAiAnalysisHistory,
  },
  {
    key: 'drive-links',
    label: 'Revoke public drive links',
    description: 'Revokes every active public download link created by this account.',
    warning: 'Recipients will no longer be able to use existing public links. Owner-visible access logs remain.',
    resultLabel: 'Public drive link revocation',
    run: revokePrivacyPublicDownloadLinks,
  },
  {
    key: 'travel-media',
    label: 'Revoke travel public media',
    description: 'Turns off public travel plan sharing and community-visible travel media surfaces.',
    warning: 'Existing public travel pages and media token access will stop working.',
    resultLabel: 'Travel public media revocation',
    run: revokePrivacyTravelPublicMediaShares,
  },
  {
    key: 'photo-location',
    label: 'Remove photo location metadata',
    description: 'Clears derived GPS latitude, longitude, and extraction timestamps stored for travel media.',
    warning: 'Some photos may disappear from photo maps or location-based clusters.',
    resultLabel: 'Photo location metadata removal',
    run: removePrivacyPhotoLocationMetadata,
  },
  {
    key: 'cleanup',
    label: 'Clean sensitive derived data',
    description: 'Deletes AI history, revokes public links, revokes travel public media, and removes photo GPS metadata.',
    warning: 'Multiple privacy cleanup actions will run together. Download anything you need first.',
    resultLabel: 'Sensitive derived data cleanup',
    run: cleanupPrivacySensitiveData,
  },
]

const pageCount = computed(() => Math.max(state.pageInfo.totalPages || 0, 1))
const securityModeLabel = computed(() => (security.mode === 'password' ? 'Password' : 'Secondary PIN'))
const securitySaveLabel = computed(() => (security.mode === 'password' ? 'Change password' : 'Change secondary PIN'))
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
    aiAnalysisHistoriesDeleted: 'Deleted AI analysis histories',
    publicDownloadLinksRevoked: 'Revoked public drive links',
    travelPublicMediaSharesRevoked: 'Revoked travel public media shares',
    photoLocationMetadataRemoved: 'Removed photo location metadata rows',
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
    state.successMessage = 'Your inquiry was sent to the administrator.'
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
        throw new Error('Password must be at least 8 characters.')
      }
      if (security.newPassword !== security.confirmPassword) {
        throw new Error('Password confirmation does not match.')
      }

      await changeProfilePassword({
        secondaryPin: security.verifiedSecondaryPin,
        newPassword: security.newPassword,
      })
      state.successMessage = 'Password changed.'
    } else {
      if (!/^\d{8}$/.test(security.newSecondaryPin.trim())) {
        throw new Error('Secondary PIN must be exactly 8 digits.')
      }
      if (security.newSecondaryPin !== security.confirmSecondaryPin) {
        throw new Error('Secondary PIN confirmation does not match.')
      }

      await changeProfileSecondaryPin({
        secondaryPin: security.verifiedSecondaryPin,
        newSecondaryPin: security.newSecondaryPin,
      })
      state.successMessage = 'Secondary PIN changed.'
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

  const confirmed = window.confirm(`${action.label}\n\n${action.warning}\n\nContinue?`)
  if (!confirmed) {
    return
  }

  resetPrivacyMessages()
  privacy.busyAction = action.key
  privacy.lastActionLabel = action.resultLabel
  privacy.lastResult = null

  try {
    privacy.lastResult = await action.run()
    privacy.successMessage = `${action.resultLabel} completed.`
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
      throw new Error('Export start date cannot be later than the end date.')
    }
    if (!privacy.secondaryPin.trim()) {
      throw new Error('Enter your secondary PIN before exporting data.')
    }

    privacy.exporting = true
    await verifyProfileSecondaryPin(privacy.secondaryPin)
    await downloadPrivacyDataExport({
      from: privacy.exportFrom || undefined,
      to: privacy.exportTo || undefined,
    })
    privacy.successMessage = 'Your encrypted data archive download has started.'
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
          <h2>Profile</h2>
          <p>Manage account security, privacy controls, data export, and support requests.</p>
        </div>
      </div>
      <div class="summary-grid profile-summary-grid">
        <article class="summary-card">
          <span>Login ID</span>
          <strong>{{ currentUser.loginId }}</strong>
        </article>
        <article class="summary-card">
          <span>Display name</span>
          <strong>{{ currentUser.displayName }}</strong>
        </article>
        <article class="summary-card">
          <span>Role</span>
          <strong>{{ currentUser.admin ? 'Admin' : 'User' }}</strong>
        </article>
      </div>
      <div class="profile-security-actions">
        <button class="button button--ghost" type="button" @click="openSecurityGate('password')">Change password</button>
        <button class="button button--ghost" type="button" @click="openSecurityGate('secondary-pin')">Change secondary PIN</button>
      </div>
    </section>

    <div v-if="state.successMessage" class="feedback feedback--success">{{ state.successMessage }}</div>
    <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>

    <section class="panel profile-privacy-panel" aria-labelledby="privacy-panel-title">
      <div class="panel__header">
        <div>
          <h2 id="privacy-panel-title">Privacy controls</h2>
          <p>Control AI analysis history, public links, travel media exposure, stored photo GPS data, and personal data export.</p>
        </div>
        <span class="panel__badge">Secondary PIN protected</span>
      </div>

      <div class="privacy-export-card" data-testid="privacy-data-export-card">
        <div>
          <strong>Download my data</strong>
          <p>Download ledger CSV and export metadata as a secondary-PIN-encrypted ZIP archive.</p>
          <small>Current archive includes ledger CSV and safe manifests only; binary photos/files require a future async export job.</small>
        </div>
        <div class="privacy-export-card__range" aria-label="Export date range">
          <label class="field">
            <span class="field__label">From</span>
            <input v-model="privacy.exportFrom" data-testid="privacy-export-from" type="date" :disabled="isPrivacyBusy" />
          </label>
          <label class="field">
            <span class="field__label">To</span>
            <input v-model="privacy.exportTo" data-testid="privacy-export-to" type="date" :disabled="isPrivacyBusy" />
          </label>
        </div>
        <button class="button button--primary" data-testid="privacy-export-open" type="button" :disabled="isPrivacyBusy" @click="openPrivacyExportGate">
          {{ privacy.exporting ? 'Exporting...' : 'Export data' }}
        </button>
      </div>

      <div class="privacy-action-grid">
        <article v-for="action in privacyActions" :key="action.key" class="privacy-action-card" :data-testid="`privacy-action-${action.key}`">
          <div>
            <h3>{{ action.label }}</h3>
            <p>{{ action.description }}</p>
            <small>{{ action.warning }}</small>
          </div>
          <button
            class="button button--ghost privacy-action-card__button"
            :data-testid="`privacy-action-run-${action.key}`"
            type="button"
            :disabled="isPrivacyBusy"
            @click="runPrivacyAction(action)"
          >
            {{ privacy.busyAction === action.key ? 'Processing...' : 'Run' }}
          </button>
        </article>
      </div>

      <div v-if="privacy.successMessage" class="feedback feedback--success" data-testid="privacy-success-message" aria-live="polite">{{ privacy.successMessage }}</div>
      <div v-if="privacy.errorMessage" class="feedback feedback--error" data-testid="privacy-error-message" aria-live="assertive">{{ privacy.errorMessage }}</div>

      <div v-if="privacyResultRows.length" class="privacy-result-list" data-testid="privacy-action-result" aria-live="polite">
        <strong>{{ privacy.lastActionLabel }} result</strong>
        <dl>
          <template v-for="row in privacyResultRows" :key="row.key">
            <dt>{{ row.label }}</dt>
            <dd>{{ formatNumber(row.value) }}</dd>
          </template>
        </dl>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>Send support inquiry</h2>
          <p>Attach an image if it helps explain the issue. Only you and administrators can see it.</p>
        </div>
      </div>

      <form class="stack-form" @submit.prevent="handleSubmitInquiry">
        <input v-model="form.title" type="text" maxlength="140" placeholder="Inquiry title" :disabled="state.sending" />
        <textarea v-model="form.content" rows="6" placeholder="Describe the issue or request." :disabled="state.sending" />
        <input ref="attachmentInput" type="file" accept="image/*" :disabled="state.sending" @change="handleAttachmentChange" />
        <p class="field__hint">Attachment is optional.</p>
        <button class="button button--primary" type="submit" :disabled="state.sending">
          {{ state.sending ? 'Sending...' : 'Send inquiry' }}
        </button>
      </form>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>My inquiries</h2>
          <p>Review support status and administrator replies.</p>
        </div>
        <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadInquiries(state.pageInfo.page)">
          {{ state.loading ? 'Loading...' : 'Refresh' }}
        </button>
      </div>

      <p v-if="state.loading" class="panel__empty">Loading inquiries.</p>
      <div v-else-if="state.inquiries.length" class="support-inquiry-list support-inquiry-list--compact">
        <article v-for="inquiry in state.inquiries" :key="inquiry.id" class="support-inquiry-card support-inquiry-card--compact">
          <button class="support-inquiry-row" type="button" @click="toggleInquiry(inquiry.id)">
            <div class="support-inquiry-row__summary">
              <strong class="support-inquiry-row__title">{{ inquiry.title }}</strong>
              <span :class="['entry-type-pill', inquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                {{ statusLabel[inquiry.status] ?? inquiry.status }}
              </span>
            </div>
            <div class="support-inquiry-row__meta">
              <small>{{ formatDateTime(inquiry.createdAt) }}</small>
              <span class="support-inquiry-row__toggle">{{ state.expandedInquiryId === inquiry.id ? 'Collapse' : 'Open' }}</span>
            </div>
          </button>

          <div v-if="state.expandedInquiryId === inquiry.id" class="support-inquiry-row__body">
            <p class="support-inquiry-content">{{ inquiry.content }}</p>

            <div v-if="inquiry.attachmentUrl" class="support-inquiry-attachment">
              <a class="button button--ghost" :href="inquiry.attachmentUrl" target="_blank" rel="noreferrer">View attachment</a>
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
                <strong>Admin reply</strong>
                <small>{{ inquiry.repliedByDisplayName || inquiry.repliedByLoginId || 'Admin' }} - {{ formatDateTime(inquiry.repliedAt) }}</small>
              </div>
              <p>{{ inquiry.replyContent }}</p>
            </div>
          </div>
        </article>
      </div>
      <p v-else class="panel__empty">No inquiries yet.</p>

      <div v-if="!state.loading && state.pageInfo.totalElements > 0" class="panel__actions">
        <button class="button button--ghost" type="button" :disabled="state.pageInfo.page <= 0" @click="changePage(state.pageInfo.page - 1)">Previous</button>
        <span>{{ state.pageInfo.page + 1 }} / {{ pageCount }}</span>
        <button class="button button--ghost" type="button" :disabled="state.pageInfo.page + 1 >= pageCount" @click="changePage(state.pageInfo.page + 1)">Next</button>
      </div>
    </section>

    <div v-if="privacy.exportGateVisible" class="travel-modal" @click.self="closePrivacyExportGate">
      <div class="travel-modal__dialog profile-security-modal" data-testid="privacy-export-dialog" role="dialog" aria-modal="true" aria-labelledby="privacy-export-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="privacy-export-title">Confirm data export</h2>
            <p>This archive can contain sensitive financial data. Confirm your current secondary PIN first.</p>
          </div>
          <button class="button button--ghost" type="button" :disabled="privacy.exporting" @click="closePrivacyExportGate">Close</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="privacy.errorMessage" class="feedback feedback--error" data-testid="privacy-export-error-message" aria-live="assertive">{{ privacy.errorMessage }}</div>
          <label class="field">
            <span class="field__label">Current secondary PIN</span>
            <input
              v-model="privacy.secondaryPin"
              data-testid="privacy-export-secondary-pin"
              type="password"
              inputmode="numeric"
              autocomplete="one-time-code"
              pattern="[0-9]*"
              maxlength="8"
              placeholder="8 digits"
              :disabled="privacy.exporting"
              @keyup.enter="handlePrivacyExport"
            />
          </label>
          <p class="field__hint">The downloaded ZIP is encrypted with the verified secondary PIN.</p>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="privacy.exporting" @click="closePrivacyExportGate">Cancel</button>
          <button class="button button--primary" type="button" :disabled="privacy.exporting" @click="handlePrivacyExport">
            {{ privacy.exporting ? 'Verifying and creating...' : 'Verify and download' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="security.gateVisible" class="travel-modal" @click.self="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal" role="dialog" aria-modal="true" aria-labelledby="security-gate-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="security-gate-title">Unlock {{ securityModeLabel }}</h2>
            <p>Enter your current secondary PIN before changing sensitive account credentials.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">Close</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>
          <label class="field">
            <span class="field__label">Current secondary PIN</span>
            <input v-model="security.secondaryPin" type="password" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*" maxlength="8" placeholder="8 digits" :disabled="security.verifying" />
          </label>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="security.verifying" @click="closeSecurityModal">Cancel</button>
          <button class="button button--primary" type="button" :disabled="security.verifying" @click="handleVerifySecondaryPin">
            {{ security.verifying ? 'Verifying...' : 'Verify' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="security.changeVisible" class="travel-modal" @click.self="closeSecurityModal">
      <div class="travel-modal__dialog profile-security-modal" role="dialog" aria-modal="true" aria-labelledby="security-change-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="security-change-title">{{ securitySaveLabel }}</h2>
            <p>{{ security.mode === 'password' ? 'The new password is applied immediately.' : 'The new secondary PIN must be exactly 8 digits.' }}</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeSecurityModal">Close</button>
        </div>

        <div class="travel-modal__body">
          <div v-if="security.errorMessage" class="feedback feedback--error">{{ security.errorMessage }}</div>

          <template v-if="security.mode === 'password'">
            <label class="field">
              <span class="field__label">New password</span>
              <input v-model="security.newPassword" type="password" placeholder="At least 8 characters" :disabled="security.saving" />
            </label>
            <label class="field">
              <span class="field__label">Confirm new password</span>
              <input v-model="security.confirmPassword" type="password" placeholder="Enter it again" :disabled="security.saving" />
            </label>
          </template>

          <template v-else>
            <label class="field">
              <span class="field__label">New secondary PIN</span>
              <input v-model="security.newSecondaryPin" type="password" inputmode="numeric" autocomplete="new-password" pattern="[0-9]*" maxlength="8" placeholder="8 digits" :disabled="security.saving" />
            </label>
            <label class="field">
              <span class="field__label">Confirm new secondary PIN</span>
              <input v-model="security.confirmSecondaryPin" type="password" inputmode="numeric" autocomplete="new-password" pattern="[0-9]*" maxlength="8" placeholder="Enter it again" :disabled="security.saving" />
            </label>
          </template>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="security.saving" @click="closeSecurityModal">Cancel</button>
          <button class="button button--primary" type="button" :disabled="security.saving" @click="handleCredentialChange">
            {{ security.saving ? 'Saving...' : securitySaveLabel }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
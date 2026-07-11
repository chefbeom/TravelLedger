<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  fetchLayoutSetting,
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  saveLayoutSetting,
} from '../lib/api'
import {
  NOTIFICATION_CATEGORY_OPTIONS,
  NOTIFICATION_PREFERENCE_SCOPE,
  NOTIFICATION_PREFERENCE_VERSION,
  createDefaultNotificationPreferences,
  normalizeNotificationPreferences,
  notificationCategoryLabel,
  resolveNotificationCategory,
} from '../lib/notificationPreferences'

const props = defineProps({
  embedded: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['unread-count-change', 'open-target', 'preferences-change'])

const PAGE_SIZE_OPTIONS = [10, 20, 50]
const DEFAULT_PAGE_SIZE = 10

const notifications = ref([])
const unreadCount = ref(0)
const pageInfo = ref({ page: 0, size: DEFAULT_PAGE_SIZE, totalElements: 0, totalPages: 0 })
const unreadOnly = ref(false)
const notificationPreferences = ref(createDefaultNotificationPreferences())
const isLoading = ref(false)
const isMutating = ref(false)
const isPreferencesOpen = ref(false)
const isPreferencesLoading = ref(false)
const isPreferencesSaving = ref(false)
const errorMessage = ref('')
const preferenceErrorMessage = ref('')

const emptyMessage = computed(() => (
  unreadOnly.value ? '읽지 않은 알림이 없습니다.' : '표시할 알림이 없습니다.'
))
const pageCount = computed(() => Math.max(1, Number(pageInfo.value.totalPages || 0)))
const hasNotifications = computed(() => Number(pageInfo.value.totalElements || 0) > 0)
const pageRangeLabel = computed(() => {
  const total = Number(pageInfo.value.totalElements || 0)
  if (!total) return '표시할 알림 없음'

  const size = Math.max(1, Number(pageInfo.value.size || DEFAULT_PAGE_SIZE))
  const start = Number(pageInfo.value.page || 0) * size + 1
  const end = Math.min(start + notifications.value.length - 1, total)
  return `${start}~${end} / 총 ${total}건`
})

function clonePreferences(value) {
  return JSON.parse(JSON.stringify(normalizeNotificationPreferences(value)))
}

function formatDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function setUnreadCount(value) {
  unreadCount.value = Math.max(0, Number(value || 0))
  emit('unread-count-change', unreadCount.value)
}

function notificationTone(type) {
  const normalized = String(type || '').toUpperCase()
  if (normalized.includes('FAILED') || normalized.includes('ERROR') || normalized.includes('BACKUP')) return 'danger'
  if (normalized.includes('SHARED') || normalized.includes('SHARE')) return 'share'
  if (normalized.includes('AI') || normalized.includes('OCR')) return 'ai'
  if (normalized.includes('SUCCESS') || normalized.includes('DONE') || normalized.includes('REACHED')) return 'success'
  return 'default'
}

function notificationTargetLabel(targetUrl) {
  const target = String(targetUrl || '')
  if (target.startsWith('/calendar')) return '가계부 · 이미지 분석 및 검수'
  if (target.startsWith('/statistics')) return '가계부 분석 · AI 분석'
  if (target.startsWith('/household')) return '가계부 · 목표 관리'
  if (target.startsWith('/travel-money')) return '여행 · 여행 가계부'
  if (target.startsWith('/travel')) return '여행'
  if (target.startsWith('/drive')) return '드라이브 · 공유 파일'
  if (target.startsWith('/profile')) return '프로필 · 개인정보 관리'
  if (target.startsWith('/admin')) return '관리자 · 운영 관리'
  return '알림 센터'
}

function notificationInfo(notification) {
  const type = String(notification?.type || '').toUpperCase()
  const category = resolveNotificationCategory(type)
  let source = notificationCategoryLabel(type)
  let event = '상태 알림'

  if (type.includes('AI_IMAGE_ANALYSIS_FAILED')) {
    source = '가계부 · 이미지 분석'
    event = '분석 실패'
  } else if (type.includes('AI_ANALYSIS_DONE')) {
    source = '가계부 분석 · AI 분석'
    event = '분석 완료'
  } else if (type.includes('AI_OR_OCR_FAILED')) {
    source = '가계부 분석 · AI 분석'
    event = '분석 실패'
  } else if (type.includes('SHARED_FILE_RECEIVED')) {
    source = '드라이브 · 파일 공유'
    event = '공유 파일 도착'
  } else if (type.includes('TRAVEL_REMINDER')) {
    source = '여행 · 일정'
    event = '출발 일정 알림'
  } else if (type.includes('TRAVEL_BUDGET')) {
    source = '여행 · 가계부'
    event = '예산 초과'
  } else if (type.includes('HOUSEHOLD_GOAL')) {
    source = '가계부 · 목표'
    event = '목표 달성'
  } else if (type.includes('PRIVACY_EXPORT')) {
    source = '프로필 · 개인정보'
    event = '데이터 내보내기 완료'
  } else if (type.includes('PRIVACY_ACTION')) {
    source = '프로필 · 개인정보'
    event = '개인정보 정리 완료'
  } else if (type.includes('BACKUP')) {
    source = '관리자 · 백업'
    event = '백업 확인 필요'
  }

  return { category, source, event, target: notificationTargetLabel(notification?.targetUrl) }
}

function clampPage(page) {
  const numericPage = Number(page || 0)
  if (!Number.isFinite(numericPage)) return 0
  return Math.min(Math.max(Math.trunc(numericPage), 0), pageCount.value - 1)
}

async function loadNotificationPreferences() {
  isPreferencesLoading.value = true
  preferenceErrorMessage.value = ''
  try {
    const response = await fetchLayoutSetting(NOTIFICATION_PREFERENCE_SCOPE)
    notificationPreferences.value = normalizeNotificationPreferences(response?.payload)
  } catch {
    notificationPreferences.value = createDefaultNotificationPreferences()
    preferenceErrorMessage.value = '알림 설정을 불러오지 못했습니다.'
  } finally {
    isPreferencesLoading.value = false
    emit('preferences-change', clonePreferences(notificationPreferences.value))
  }
}

async function updateNotificationPreferences(mutator) {
  if (isPreferencesSaving.value || isPreferencesLoading.value) return

  const previous = clonePreferences(notificationPreferences.value)
  const next = clonePreferences(notificationPreferences.value)
  mutator(next)
  notificationPreferences.value = next
  preferenceErrorMessage.value = ''
  emit('preferences-change', clonePreferences(next))
  isPreferencesSaving.value = true

  try {
    const response = await saveLayoutSetting(
      NOTIFICATION_PREFERENCE_SCOPE,
      next,
      NOTIFICATION_PREFERENCE_VERSION,
    )
    notificationPreferences.value = normalizeNotificationPreferences(response?.payload || next)
    emit('preferences-change', clonePreferences(notificationPreferences.value))
  } catch (error) {
    notificationPreferences.value = previous
    emit('preferences-change', clonePreferences(previous))
    preferenceErrorMessage.value = error?.message || '알림 설정을 저장하지 못했습니다.'
  } finally {
    isPreferencesSaving.value = false
  }
}

function changeNotificationEnabled(event) {
  updateNotificationPreferences((next) => {
    next.enabled = Boolean(event?.target?.checked)
  })
}

function changeNotificationCategory(category, event) {
  updateNotificationPreferences((next) => {
    next.categories[category] = Boolean(event?.target?.checked)
  })
}

async function loadNotifications(page = pageInfo.value.page || 0) {
  isLoading.value = true
  errorMessage.value = ''
  const requestedPage = Math.max(0, Math.trunc(Number(page || 0)))

  try {
    const response = await fetchNotifications({
      page: requestedPage,
      size: pageInfo.value.size,
      unreadOnly: unreadOnly.value ? 'true' : undefined,
    })

    const responsePage = Number(response?.page ?? requestedPage)
    const totalPages = Number(response?.totalPages || 0)
    if (totalPages > 0 && responsePage >= totalPages) {
      await loadNotifications(totalPages - 1)
      return
    }

    notifications.value = Array.isArray(response?.content) ? response.content : []
    setUnreadCount(response?.unreadCount)
    pageInfo.value = {
      page: Math.max(0, responsePage),
      size: Number(response?.size || pageInfo.value.size || DEFAULT_PAGE_SIZE),
      totalElements: Number(response?.totalElements || 0),
      totalPages,
    }
  } catch (error) {
    errorMessage.value = error?.message || '알림을 불러오지 못했습니다.'
  } finally {
    isLoading.value = false
  }
}

async function changePage(page) {
  const nextPage = clampPage(page)
  if (nextPage === pageInfo.value.page || isLoading.value) return
  await loadNotifications(nextPage)
}

async function changePageSize(event) {
  const nextSize = Number(event?.target?.value || DEFAULT_PAGE_SIZE)
  pageInfo.value = {
    ...pageInfo.value,
    size: PAGE_SIZE_OPTIONS.includes(nextSize) ? nextSize : DEFAULT_PAGE_SIZE,
    page: 0,
  }
  await loadNotifications(0)
}

async function toggleUnreadOnly() {
  unreadOnly.value = !unreadOnly.value
  await loadNotifications(0)
}

async function markRead(notification) {
  if (!notification?.id || notification.read || isMutating.value) return
  isMutating.value = true
  errorMessage.value = ''
  try {
    const response = await markNotificationRead(notification.id)
    notifications.value = notifications.value.map((item) => (
      item.id === notification.id
        ? { ...item, read: true, readAt: response?.processedAt || new Date().toISOString() }
        : item
    ))
    setUnreadCount(response?.unreadCount ?? Math.max(0, unreadCount.value - 1))
    if (unreadOnly.value) await loadNotifications(pageInfo.value.page)
  } catch (error) {
    errorMessage.value = error?.message || '알림을 읽음 처리하지 못했습니다.'
  } finally {
    isMutating.value = false
  }
}

async function markAllRead() {
  if (!unreadCount.value || isMutating.value) return
  isMutating.value = true
  errorMessage.value = ''
  try {
    const response = await markAllNotificationsRead()
    setUnreadCount(response?.unreadCount)
    if (unreadOnly.value) {
      notifications.value = []
      pageInfo.value = { ...pageInfo.value, page: 0, totalElements: 0, totalPages: 0 }
    } else {
      const readAt = response?.processedAt || new Date().toISOString()
      notifications.value = notifications.value.map((item) => ({ ...item, read: true, readAt }))
    }
  } catch (error) {
    errorMessage.value = error?.message || '모든 알림을 읽음 처리하지 못했습니다.'
  } finally {
    isMutating.value = false
  }
}

function openTarget(notification) {
  const target = String(notification?.targetUrl || '')
  if (!target.startsWith('/')) return
  emit('open-target', target)
}

onMounted(async () => {
  await Promise.all([loadNotificationPreferences(), loadNotifications(0)])
})
</script>

<template>
  <div :class="['workspace-stack notification-center', { 'notification-center--embedded': embedded }]">
    <section class="panel notification-hero">
      <div class="panel__header notification-hero__header">
        <div>
          <p class="panel__eyebrow">알림 센터</p>
          <h2>새 알림 {{ unreadCount }}건</h2>
        </div>
        <button
          class="button button--ghost notification-settings-button"
          type="button"
          :aria-expanded="isPreferencesOpen"
          @click="isPreferencesOpen = !isPreferencesOpen"
        >
          알림 설정
        </button>
      </div>

      <section v-if="isPreferencesOpen" class="notification-preferences" aria-label="알림 수신 설정">
        <div class="notification-preferences__header">
          <strong>알림 수신</strong>
          <label class="notification-preferences__master">
            <input
              type="checkbox"
              :checked="notificationPreferences.enabled"
              :disabled="isPreferencesLoading || isPreferencesSaving"
              @change="changeNotificationEnabled"
            >
            <span>{{ notificationPreferences.enabled ? '켜짐' : '꺼짐' }}</span>
          </label>
        </div>
        <div class="notification-preferences__categories">
          <label
            v-for="option in NOTIFICATION_CATEGORY_OPTIONS"
            :key="option.key"
            class="notification-preferences__option"
            :class="{ 'notification-preferences__option--disabled': !notificationPreferences.enabled }"
          >
            <input
              type="checkbox"
              :checked="notificationPreferences.categories[option.key]"
              :disabled="!notificationPreferences.enabled || isPreferencesLoading || isPreferencesSaving"
              @change="changeNotificationCategory(option.key, $event)"
            >
            <span>{{ option.label }}</span>
          </label>
        </div>
        <p v-if="preferenceErrorMessage" class="notification-preferences__error">{{ preferenceErrorMessage }}</p>
      </section>

      <div class="notification-toolbar">
        <div class="notification-toolbar__filters">
          <button class="button button--ghost" type="button" :disabled="isLoading" @click="toggleUnreadOnly">
            {{ unreadOnly ? '전체 알림 보기' : '읽지 않은 알림만 보기' }}
          </button>
          <label class="notification-page-size">
            <span>페이지당</span>
            <select :value="pageInfo.size" :disabled="isLoading" @change="changePageSize">
              <option v-for="size in PAGE_SIZE_OPTIONS" :key="size" :value="size">{{ size }}개</option>
            </select>
          </label>
        </div>
        <div class="notification-toolbar__actions">
          <span class="notification-page-summary">{{ pageRangeLabel }}</span>
          <button class="button button--primary" type="button" :disabled="isMutating || !unreadCount" @click="markAllRead">
            모두 읽음 처리
          </button>
        </div>
      </div>
    </section>

    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="panel notification-list-panel">
      <div v-if="isLoading" class="notification-empty">알림을 불러오는 중입니다...</div>
      <div v-else-if="!notifications.length" class="notification-empty">{{ emptyMessage }}</div>
      <template v-else>
        <article
          v-for="notification in notifications"
          :key="notification.id"
          class="notification-card"
          :class="[`notification-card--${notificationTone(notification.type)}`, { 'notification-card--unread': !notification.read }]"
        >
          <div class="notification-card__status" :class="{ 'notification-card__status--read': notification.read }">
            {{ notification.read ? '읽음' : '새 알림' }}
          </div>
          <div class="notification-card__body">
            <div class="notification-card__meta">
              <span class="notification-card__source">{{ notificationInfo(notification).source }}</span>
              <span class="notification-card__event">{{ notificationInfo(notification).event }}</span>
              <span>{{ formatDateTime(notification.createdAt) }}</span>
            </div>
            <h3>{{ notification.title || '알림' }}</h3>
            <div class="notification-card__context">
              <span>알림 대상</span>
              <strong>{{ notificationInfo(notification).target }}</strong>
            </div>
            <p>{{ notification.message || '알림 내용이 없습니다.' }}</p>
            <div class="notification-card__actions">
              <button v-if="notification.targetUrl" class="button button--ghost" type="button" @click="openTarget(notification)">
                관련 화면 열기
              </button>
              <button class="button button--secondary" type="button" :disabled="notification.read || isMutating" @click="markRead(notification)">
                {{ notification.read ? '읽음 완료' : '읽음 처리' }}
              </button>
            </div>
          </div>
        </article>
      </template>

      <div v-if="hasNotifications" class="notification-pagination">
        <button class="button button--ghost" type="button" :disabled="isLoading || pageInfo.page <= 0" @click="changePage(pageInfo.page - 1)">
          이전
        </button>
        <span>{{ pageInfo.page + 1 }} / {{ pageCount }}</span>
        <button class="button button--ghost" type="button" :disabled="isLoading || pageInfo.page + 1 >= pageCount" @click="changePage(pageInfo.page + 1)">
          다음
        </button>
      </div>
    </section>
  </div>
</template>
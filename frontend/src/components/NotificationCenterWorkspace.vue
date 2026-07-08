<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '../lib/api'

const props = defineProps({
  embedded: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['unread-count-change', 'open-target'])

const PAGE_SIZE_OPTIONS = [10, 20, 50]
const DEFAULT_PAGE_SIZE = 10

const notifications = ref([])
const unreadCount = ref(0)
const pageInfo = ref({ page: 0, size: DEFAULT_PAGE_SIZE, totalElements: 0, totalPages: 0 })
const unreadOnly = ref(false)
const isLoading = ref(false)
const isMutating = ref(false)
const errorMessage = ref('')

const emptyMessage = computed(() => (unreadOnly.value ? '읽지 않은 알림이 없습니다.' : '아직 도착한 알림이 없습니다.'))
const pageCount = computed(() => Math.max(1, Number(pageInfo.value.totalPages || 0)))
const hasNotifications = computed(() => Number(pageInfo.value.totalElements || 0) > 0)
const pageRangeLabel = computed(() => {
  const total = Number(pageInfo.value.totalElements || 0)
  if (!total) {
    return '표시할 알림 없음'
  }

  const size = Math.max(1, Number(pageInfo.value.size || DEFAULT_PAGE_SIZE))
  const start = Number(pageInfo.value.page || 0) * size + 1
  const end = Math.min(start + notifications.value.length - 1, total)
  return `${start}~${end} / 총 ${total}건`
})

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
  if (normalized.includes('AI')) return 'ai'
  if (normalized.includes('SUCCESS') || normalized.includes('DONE')) return 'success'
  return 'default'
}

function notificationTypeLabel(type) {
  const normalized = String(type || '').toUpperCase()
  if (normalized.includes('AI')) return 'AI'
  if (normalized.includes('OCR')) return 'OCR'
  if (normalized.includes('BACKUP')) return '백업'
  if (normalized.includes('RESTORE')) return '복구'
  if (normalized.includes('SHARED') || normalized.includes('SHARE')) return '공유'
  if (normalized.includes('DRIVE') || normalized.includes('FILE')) return '드라이브'
  if (normalized.includes('TRAVEL')) return '여행'
  if (normalized.includes('BUDGET')) return '예산'
  if (normalized.includes('SECURITY') || normalized.includes('LOGIN')) return '보안'
  if (normalized.includes('SYSTEM')) return '시스템'
  return '알림'
}

function clampPage(page) {
  const numericPage = Number(page || 0)
  if (!Number.isFinite(numericPage)) {
    return 0
  }
  return Math.min(Math.max(Math.trunc(numericPage), 0), pageCount.value - 1)
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
  if (nextPage === pageInfo.value.page || isLoading.value) {
    return
  }
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
    if (unreadOnly.value) {
      await loadNotifications(pageInfo.value.page)
    }
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

onMounted(() => loadNotifications(0))
</script>

<template>
  <div :class="['workspace-stack notification-center', { 'notification-center--embedded': embedded }]">
    <section class="panel notification-hero">
      <div class="panel__header notification-hero__header">
        <div>
          <p class="panel__eyebrow">알림 센터</p>
          <h2>운영, AI, OCR, 공유 알림</h2>
        </div>
        <span class="panel__badge notification-hero__badge">읽지 않음 {{ unreadCount }}건</span>
      </div>
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
              <span>{{ notificationTypeLabel(notification.type) }}</span>
              <span>{{ formatDateTime(notification.createdAt) }}</span>
            </div>
            <h3>{{ notification.title || '알림' }}</h3>
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
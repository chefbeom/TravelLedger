<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '../lib/api'

const notifications = ref([])
const unreadCount = ref(0)
const pageInfo = ref({ page: 0, size: 20, totalElements: 0, totalPages: 0 })
const unreadOnly = ref(false)
const isLoading = ref(false)
const isMutating = ref(false)
const errorMessage = ref('')

const emptyMessage = computed(() => (unreadOnly.value ? 'No unread notifications.' : 'No notifications yet.'))

function formatDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function notificationTone(type) {
  const normalized = String(type || '').toUpperCase()
  if (normalized.includes('FAILED') || normalized.includes('BACKUP')) return 'danger'
  if (normalized.includes('SHARED')) return 'share'
  if (normalized.includes('AI')) return 'ai'
  return 'default'
}

async function loadNotifications(page = pageInfo.value.page || 0) {
  isLoading.value = true
  errorMessage.value = ''
  try {
    const response = await fetchNotifications({
      page,
      size: pageInfo.value.size,
      unreadOnly: unreadOnly.value ? 'true' : undefined,
    })
    notifications.value = Array.isArray(response?.content) ? response.content : []
    unreadCount.value = Number(response?.unreadCount || 0)
    pageInfo.value = {
      page: Number(response?.page || 0),
      size: Number(response?.size || pageInfo.value.size || 20),
      totalElements: Number(response?.totalElements || 0),
      totalPages: Number(response?.totalPages || 0),
    }
  } catch (error) {
    errorMessage.value = error?.message || 'Failed to load notifications.'
  } finally {
    isLoading.value = false
  }
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
    unreadCount.value = Number(response?.unreadCount ?? Math.max(0, unreadCount.value - 1))
    if (unreadOnly.value) {
      notifications.value = notifications.value.filter((item) => !item.read)
    }
  } catch (error) {
    errorMessage.value = error?.message || 'Failed to mark notification as read.'
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
    unreadCount.value = Number(response?.unreadCount || 0)
    if (unreadOnly.value) {
      notifications.value = []
    } else {
      const readAt = response?.processedAt || new Date().toISOString()
      notifications.value = notifications.value.map((item) => ({ ...item, read: true, readAt }))
    }
  } catch (error) {
    errorMessage.value = error?.message || 'Failed to mark all notifications as read.'
  } finally {
    isMutating.value = false
  }
}

function openTarget(notification) {
  const target = String(notification?.targetUrl || '')
  if (!target.startsWith('/')) return
  window.location.assign(target)
}

onMounted(() => loadNotifications(0))
</script>

<template>
  <div class="workspace-stack notification-center">
    <section class="panel notification-hero">
      <div class="panel__header notification-hero__header">
        <div>
          <p class="panel__eyebrow">Notification center</p>
          <h2>Operations, AI, OCR, and sharing updates</h2>
          <p>Review user-scoped events from AI analysis, OCR, backups, and shared files in one place.</p>
        </div>
        <span class="panel__badge notification-hero__badge">{{ unreadCount }} unread</span>
      </div>
      <div class="notification-toolbar">
        <button class="button button--ghost" type="button" :disabled="isLoading" @click="toggleUnreadOnly">
          {{ unreadOnly ? 'Show all' : 'Unread only' }}
        </button>
        <button class="button button--primary" type="button" :disabled="isMutating || !unreadCount" @click="markAllRead">
          Mark all read
        </button>
      </div>
    </section>

    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="panel notification-list-panel">
      <div v-if="isLoading" class="notification-empty">Loading notifications...</div>
      <div v-else-if="!notifications.length" class="notification-empty">{{ emptyMessage }}</div>
      <template v-else>
        <article
          v-for="notification in notifications"
          :key="notification.id"
        class="notification-card"
        :class="[`notification-card--${notificationTone(notification.type)}`, { 'notification-card--unread': !notification.read }]"
      >
        <div class="notification-card__status" aria-hidden="true"></div>
        <div class="notification-card__body">
          <div class="notification-card__meta">
            <span>{{ notification.type || 'NOTIFICATION' }}</span>
            <span>{{ formatDateTime(notification.createdAt) }}</span>
          </div>
          <h3>{{ notification.title }}</h3>
          <p>{{ notification.message }}</p>
          <div class="notification-card__actions">
            <button v-if="notification.targetUrl" class="button button--ghost" type="button" @click="openTarget(notification)">
              Open target
            </button>
            <button class="button button--secondary" type="button" :disabled="notification.read || isMutating" @click="markRead(notification)">
              {{ notification.read ? 'Read' : 'Mark read' }}
            </button>
          </div>
        </div>
      </article>
      </template>
    </section>
  </div>
</template>
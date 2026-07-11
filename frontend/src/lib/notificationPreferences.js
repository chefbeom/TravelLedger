export const NOTIFICATION_PREFERENCE_SCOPE = 'notification-preferences'
export const NOTIFICATION_PREFERENCE_VERSION = 1

export const NOTIFICATION_CATEGORY_OPTIONS = [
  { key: 'ledger', label: '가계부 및 AI 분석' },
  { key: 'travel', label: '여행' },
  { key: 'drive', label: '드라이브 및 공유' },
  { key: 'account', label: '계정 및 개인정보' },
  { key: 'system', label: '운영 및 백업' },
]

export function createDefaultNotificationPreferences() {
  return {
    enabled: true,
    categories: Object.fromEntries(NOTIFICATION_CATEGORY_OPTIONS.map(({ key }) => [key, true])),
  }
}

export function normalizeNotificationPreferences(value) {
  const defaults = createDefaultNotificationPreferences()
  const source = value && typeof value === 'object' ? value : {}
  const categories = source.categories && typeof source.categories === 'object' ? source.categories : {}

  return {
    enabled: source.enabled !== false,
    categories: Object.fromEntries(NOTIFICATION_CATEGORY_OPTIONS.map(({ key }) => [
      key,
      categories[key] !== false,
    ])),
  }
}

export function resolveNotificationCategory(type) {
  const normalized = String(type || '').toUpperCase()
  if (normalized.includes('AI') || normalized.includes('OCR') || normalized.includes('HOUSEHOLD') || normalized.includes('GOAL')) return 'ledger'
  if (normalized.includes('TRAVEL') || normalized.includes('BUDGET')) return 'travel'
  if (normalized.includes('DRIVE') || normalized.includes('FILE') || normalized.includes('SHARE')) return 'drive'
  if (normalized.includes('PRIVACY') || normalized.includes('EXPORT') || normalized.includes('SECURITY') || normalized.includes('LOGIN')) return 'account'
  return 'system'
}

export function notificationCategoryLabel(type) {
  return NOTIFICATION_CATEGORY_LABELS[resolveNotificationCategory(type)] || '기타 알림'
}

export function isNotificationEnabled(preferences, type) {
  const normalized = normalizeNotificationPreferences(preferences)
  return normalized.enabled && normalized.categories[resolveNotificationCategory(type)] !== false
}

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import {
  changeProfilePassword,
  changeProfileSecondaryPin,
  fetchDriveProfileSettings,
  updateDriveProfileSettings,
  uploadDriveProfileImage,
  verifyProfileSecondaryPin,
} from '../lib/api'
import { formatDateTime } from '../lib/uiFormat'

const props = defineProps({
  open: {
    type: Boolean,
    default: false,
  },
  currentUser: {
    type: Object,
    required: true,
  },
})

const emit = defineEmits(['close', 'updated'])

const tabs = [
  { id: 'profile', label: '기본 프로필', description: '이름과 프로필 사진', icon: '프' },
  { id: 'security', label: '계정 보안', description: '비밀번호와 2차 PIN', icon: '보' },
  { id: 'notif', label: '알림 설정', description: '마케팅과 보안 알림', icon: '알' },
  { id: 'region', label: '언어·지역', description: '표시 언어와 지역 코드', icon: '지' },
  { id: 'storage', label: '저장소 현황', description: '사용량과 계정 이력', icon: '용' },
]

const activeTab = ref('profile')
const loading = ref(false)
const saving = ref(false)
const securitySaving = ref(false)
const feedback = ref('')
const errorMessage = ref('')

const settings = reactive({
  userId: null,
  loginId: '',
  displayName: '',
  role: '',
  active: true,
  localeCode: 'KO',
  regionCode: 'KR',
  marketingOptIn: true,
  privateProfile: false,
  emailNotification: true,
  securityNotification: true,
  profileImageUrl: '',
  driveUsedBytes: 0,
  driveFileCount: 0,
  createdAt: '',
  updatedAt: '',
})

const security = reactive({
  secondaryPin: '',
  verifiedSecondaryPin: '',
  newPassword: '',
  confirmPassword: '',
  newSecondaryPin: '',
  confirmSecondaryPin: '',
})

const resolvedRoleLabel = computed(() => {
  return settings.role || (props.currentUser.admin ? 'ADMIN' : 'USER')
})

function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (!Number.isFinite(value) || value <= 0) {
    return '0 B'
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let amount = value
  let unitIndex = 0
  while (amount >= 1024 && unitIndex < units.length - 1) {
    amount /= 1024
    unitIndex += 1
  }
  return `${amount >= 10 || unitIndex === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[unitIndex]}`
}

function setMessages(message = '', error = '') {
  feedback.value = message
  errorMessage.value = error
}

function resetSecurityForm() {
  security.secondaryPin = ''
  security.verifiedSecondaryPin = ''
  security.newPassword = ''
  security.confirmPassword = ''
  security.newSecondaryPin = ''
  security.confirmSecondaryPin = ''
}

async function loadSettings() {
  loading.value = true
  setMessages()

  try {
    const response = await fetchDriveProfileSettings()
    Object.assign(settings, response || {})
  } catch (error) {
    setMessages('', error.message)
  } finally {
    loading.value = false
  }
}

async function handleSaveProfile() {
  saving.value = true
  setMessages()

  try {
    const response = await updateDriveProfileSettings({
      displayName: settings.displayName,
      localeCode: settings.localeCode,
      regionCode: settings.regionCode,
      marketingOptIn: settings.marketingOptIn,
      privateProfile: settings.privateProfile,
      emailNotification: settings.emailNotification,
      securityNotification: settings.securityNotification,
    })
    Object.assign(settings, response || {})
    setMessages('CalenDrive 프로필 설정을 저장했습니다.')
    emit('updated', response)
  } catch (error) {
    setMessages('', error.message)
  } finally {
    saving.value = false
  }
}

async function handleProfileImageChange(event) {
  const file = event.target.files?.[0]
  if (!file) {
    return
  }

  saving.value = true
  setMessages()

  try {
    const response = await uploadDriveProfileImage(file)
    Object.assign(settings, response || {})
    setMessages('프로필 이미지를 업데이트했습니다.')
    emit('updated', response)
  } catch (error) {
    setMessages('', error.message)
  } finally {
    saving.value = false
    event.target.value = ''
  }
}

async function handleChangePassword() {
  if (security.newPassword.trim().length < 8) {
    setMessages('', '새 비밀번호는 8자 이상이어야 합니다.')
    return
  }
  if (security.newPassword !== security.confirmPassword) {
    setMessages('', '새 비밀번호 확인이 일치하지 않습니다.')
    return
  }

  securitySaving.value = true
  setMessages()

  try {
    if (!security.verifiedSecondaryPin) {
      await verifyProfileSecondaryPin(security.secondaryPin)
      security.verifiedSecondaryPin = security.secondaryPin
    }
    await changeProfilePassword({
      secondaryPin: security.verifiedSecondaryPin,
      newPassword: security.newPassword,
    })
    setMessages('비밀번호를 변경했습니다.')
    resetSecurityForm()
  } catch (error) {
    setMessages('', error.message)
  } finally {
    securitySaving.value = false
  }
}

async function handleChangeSecondaryPin() {
  if (!/^\d{8}$/.test(String(security.newSecondaryPin).trim())) {
    setMessages('', '새 2차 비밀번호는 숫자 8자리여야 합니다.')
    return
  }
  if (security.newSecondaryPin !== security.confirmSecondaryPin) {
    setMessages('', '새 2차 비밀번호 확인이 일치하지 않습니다.')
    return
  }

  securitySaving.value = true
  setMessages()

  try {
    if (!security.verifiedSecondaryPin) {
      await verifyProfileSecondaryPin(security.secondaryPin)
      security.verifiedSecondaryPin = security.secondaryPin
    }
    await changeProfileSecondaryPin({
      secondaryPin: security.verifiedSecondaryPin,
      newSecondaryPin: security.newSecondaryPin,
    })
    setMessages('2차 비밀번호를 변경했습니다.')
    resetSecurityForm()
  } catch (error) {
    setMessages('', error.message)
  } finally {
    securitySaving.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    activeTab.value = 'profile'
    resetSecurityForm()
    loadSettings()
  },
)
</script>

<template>
  <div v-if="open" class="travel-modal" @click.self="emit('close')">
    <div class="travel-modal__dialog drive-profile-modal">
      <div class="travel-modal__header">
        <div>
          <h2>CalenDrive 프로필</h2>
          <p>원본 드라이브 프로젝트의 좌측 탭형 흐름을 유지하면서 현재 CalenDrive 계정 기능을 한 모달에 정리했습니다.</p>
        </div>
        <button class="button button--ghost" type="button" @click="emit('close')">닫기</button>
      </div>

      <div class="travel-modal__body drive-profile-modal__body">
        <div class="drive-profile-modal__shell">
          <aside class="drive-profile-modal__sidebar">
            <div class="drive-profile-modal__hero">
              <img
                :src="settings.profileImageUrl || `https://ui-avatars.com/api/?name=${encodeURIComponent(settings.displayName || currentUser.displayName)}&background=4f8cff&color=ffffff`"
                :alt="settings.displayName || currentUser.displayName"
                class="drive-profile-modal__avatar"
              />
              <div class="drive-profile-modal__hero-copy">
                <strong>{{ settings.displayName || currentUser.displayName }}</strong>
                <small>{{ settings.loginId || currentUser.loginId }}</small>
                <small>{{ resolvedRoleLabel }}</small>
              </div>
            </div>

            <nav class="drive-profile-modal__nav">
              <button
                v-for="tab in tabs"
                :key="tab.id"
                class="drive-profile-modal__nav-item"
                :class="{ 'drive-profile-modal__nav-item--active': activeTab === tab.id }"
                type="button"
                @click="activeTab = tab.id"
              >
                <span class="drive-profile-modal__nav-icon">{{ tab.icon }}</span>
                <span class="drive-profile-modal__nav-copy">
                  <strong>{{ tab.label }}</strong>
                  <small>{{ tab.description }}</small>
                </span>
              </button>
            </nav>
          </aside>

          <div class="drive-profile-modal__content">
            <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
            <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>
            <p v-if="loading" class="panel__empty">CalenDrive 프로필을 불러오는 중입니다.</p>

            <template v-else>
              <section v-if="activeTab === 'profile'" class="panel panel--compact drive-profile-modal__section">
                <div class="panel__header">
                  <div>
                    <h3>기본 프로필</h3>
                    <p>표시 이름과 프로필 이미지를 정리하고, 로그인 계정 상태를 확인합니다.</p>
                  </div>
                </div>

                <div class="drive-profile-modal__profile-grid">
                  <div class="drive-profile-modal__image-card">
                    <img
                      :src="settings.profileImageUrl || `https://ui-avatars.com/api/?name=${encodeURIComponent(settings.displayName || currentUser.displayName)}&background=4f8cff&color=ffffff`"
                      :alt="settings.displayName || currentUser.displayName"
                      class="drive-profile-modal__avatar drive-profile-modal__avatar--large"
                    />
                    <label class="button button--ghost drive-profile-modal__upload">
                      프로필 이미지 변경
                      <input type="file" accept="image/*" hidden :disabled="saving" @change="handleProfileImageChange" />
                    </label>
                  </div>

                  <div class="travel-form-grid">
                    <label class="field">
                      <span class="field__label">표시 이름</span>
                      <input v-model="settings.displayName" type="text" />
                    </label>
                    <label class="field">
                      <span class="field__label">로그인 ID</span>
                      <input :value="settings.loginId || currentUser.loginId" type="text" disabled />
                    </label>
                    <label class="field">
                      <span class="field__label">권한</span>
                      <input :value="resolvedRoleLabel" type="text" disabled />
                    </label>
                    <label class="field">
                      <span class="field__label">계정 상태</span>
                      <input :value="settings.active ? '활성' : '비활성'" type="text" disabled />
                    </label>
                  </div>
                </div>
              </section>

              <section v-else-if="activeTab === 'security'" class="panel panel--compact drive-profile-modal__section">
                <div class="panel__header">
                  <div>
                    <h3>계정 보안</h3>
                    <p>현재 2차 PIN을 확인한 뒤 비밀번호와 2차 비밀번호를 각각 안전하게 갱신합니다.</p>
                  </div>
                </div>

                <div class="travel-form-grid">
                  <label class="field field--full">
                    <span class="field__label">현재 2차 비밀번호</span>
                    <input v-model="security.secondaryPin" type="password" inputmode="numeric" maxlength="8" placeholder="숫자 8자리" />
                  </label>
                  <label class="field">
                    <span class="field__label">새 비밀번호</span>
                    <input v-model="security.newPassword" type="password" placeholder="8자 이상" />
                  </label>
                  <label class="field">
                    <span class="field__label">새 비밀번호 확인</span>
                    <input v-model="security.confirmPassword" type="password" />
                  </label>
                  <label class="field">
                    <span class="field__label">새 2차 비밀번호</span>
                    <input v-model="security.newSecondaryPin" type="password" inputmode="numeric" maxlength="8" placeholder="숫자 8자리" />
                  </label>
                  <label class="field">
                    <span class="field__label">새 2차 비밀번호 확인</span>
                    <input v-model="security.confirmSecondaryPin" type="password" inputmode="numeric" maxlength="8" />
                  </label>
                </div>

                <div class="entry-editor__actions">
                  <button class="button button--ghost" type="button" :disabled="securitySaving" @click="resetSecurityForm">초기화</button>
                  <button class="button button--primary" type="button" :disabled="securitySaving" @click="handleChangePassword">
                    {{ securitySaving ? '처리 중' : '비밀번호 변경' }}
                  </button>
                  <button class="button button--primary" type="button" :disabled="securitySaving" @click="handleChangeSecondaryPin">
                    {{ securitySaving ? '처리 중' : '2차 PIN 변경' }}
                  </button>
                </div>
              </section>

              <section v-else-if="activeTab === 'notif'" class="panel panel--compact drive-profile-modal__section">
                <div class="panel__header">
                  <div>
                    <h3>알림 설정</h3>
                    <p>현재 구현되어 있는 마케팅, 이메일, 보안 알림과 비공개 프로필 설정을 그대로 묶었습니다.</p>
                  </div>
                </div>

                <div class="drive-profile-modal__stack">
                  <label class="checkbox-row">
                    <input v-model="settings.marketingOptIn" type="checkbox" />
                    <span>마케팅 정보 수신</span>
                  </label>
                  <label class="checkbox-row">
                    <input v-model="settings.emailNotification" type="checkbox" />
                    <span>이메일 알림 수신</span>
                  </label>
                  <label class="checkbox-row">
                    <input v-model="settings.securityNotification" type="checkbox" />
                    <span>보안 알림 수신</span>
                  </label>
                  <label class="checkbox-row">
                    <input v-model="settings.privateProfile" type="checkbox" />
                    <span>비공개 프로필 모드</span>
                  </label>
                </div>
              </section>

              <section v-else-if="activeTab === 'region'" class="panel panel--compact drive-profile-modal__section">
                <div class="panel__header">
                  <div>
                    <h3>언어 · 지역</h3>
                    <p>드라이브 프로젝트의 지역 탭 감각을 유지하면서 현재 localeCode, regionCode 저장값을 그대로 다룹니다.</p>
                  </div>
                </div>

                <div class="travel-form-grid">
                  <label class="field">
                    <span class="field__label">언어 코드</span>
                    <input v-model="settings.localeCode" type="text" maxlength="10" placeholder="KO" />
                  </label>
                  <label class="field">
                    <span class="field__label">지역 코드</span>
                    <input v-model="settings.regionCode" type="text" maxlength="10" placeholder="KR" />
                  </label>
                </div>
              </section>

              <section v-else class="panel panel--compact drive-profile-modal__section">
                <div class="panel__header">
                  <div>
                    <h3>저장소 현황</h3>
                    <p>현재 사용자 기준 사용량, 파일 수, 생성 시각과 최근 변경 시각을 읽기 전용으로 보여줍니다.</p>
                  </div>
                </div>

                <div class="summary-grid">
                  <article class="summary-card">
                    <span>사용 중인 용량</span>
                    <strong>{{ formatBytes(settings.driveUsedBytes) }}</strong>
                  </article>
                  <article class="summary-card">
                    <span>보유 파일 수</span>
                    <strong>{{ settings.driveFileCount || 0 }}</strong>
                  </article>
                  <article class="summary-card">
                    <span>생성 시각</span>
                    <strong>{{ formatDateTime(settings.createdAt) }}</strong>
                  </article>
                  <article class="summary-card">
                    <span>최근 변경</span>
                    <strong>{{ formatDateTime(settings.updatedAt) }}</strong>
                  </article>
                </div>
              </section>
            </template>
          </div>
        </div>
      </div>

      <div class="travel-modal__footer">
        <button class="button button--ghost" type="button" @click="emit('close')">닫기</button>
        <button
          v-if="activeTab !== 'security' && activeTab !== 'storage'"
          class="button button--primary"
          type="button"
          :disabled="saving || loading"
          @click="handleSaveProfile"
        >
          {{ saving ? '저장 중' : '설정 저장' }}
        </button>
      </div>
    </div>
  </div>
</template>

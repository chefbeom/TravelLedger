<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import FeatureLauncher from './components/FeatureLauncher.vue'
import FamilyAlbumWorkspace from './components/FamilyAlbumWorkspace.vue'
import HouseholdWorkspace from './components/HouseholdWorkspace.vue'
import TravelHubWorkspace from './components/TravelHubWorkspace.vue'
import { fetchCurrentUser, login, logout as logoutRequest } from './lib/api'

const featureItems = [
  {
    key: 'household',
    number: '1',
    title: '가계부',
    description: '가계부, 통계, 검색과 관리 기능을 한 화면에서 바로 사용할 수 있습니다.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: '여행 예산',
    description: '여행 생성, 예산 계획, 실제 지출과 통계를 함께 관리합니다.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: '여행 로그',
    description: '여행 기록, 경로, 사진과 GPX 파일을 묶어서 관리합니다.',
  },
  {
    key: 'photo-album',
    number: '4',
    title: '여행 사진',
    description: '여행 기록과 연결된 사진을 카드 형태로 모아 볼 수 있습니다.',
  },
  {
    key: 'family-album',
    number: '5',
    title: '가족 앨범',
    description: '가족 카테고리별 사진과 영상을 업로드하고 공유할 수 있습니다.',
  },
]

const routeMeta = {
  launcher: {
    title: '기능 선택',
    description: '원하는 기능 페이지를 골라 바로 들어갈 수 있습니다.',
  },
  household: {
    title: '가계부',
    description: '달력 가계부, 통계, 검색, 카테고리 관리까지 한 번에 확인합니다.',
  },
  'travel-money': {
    title: '여행 예산',
    description: '여행 예산과 실제 지출 기록을 함께 관리하는 페이지입니다.',
  },
  'travel-log': {
    title: '여행 로그',
    description: '여행 기록, 이동 경로, 메모와 사진을 정리하는 페이지입니다.',
  },
  'photo-album': {
    title: '여행 사진',
    description: '여행 로그에서 공유한 사진을 모아보는 페이지입니다.',
  },
  'family-album': {
    title: '가족 앨범',
    description: '가족 구성원과 함께 사진과 영상을 나누는 페이지입니다.',
  },
}

const authChecked = ref(false)
const currentUser = ref(null)
const isSubmitting = ref(false)
const activeSubmit = ref('')
const successMessage = ref('')
const errorMessage = ref('')
const activeRoute = ref(resolveRoute(window.location.hash))

const loginForm = reactive({
  loginId: 'hana',
  password: 'test1234',
  rememberDevice: true,
})

const pageMeta = computed(() => routeMeta[activeRoute.value] || routeMeta.launcher)

function resolveRoute(hash) {
  const route = String(hash || '').replace(/^#/, '')
  return routeMeta[route] ? route : 'launcher'
}

function setFeedback(message = '', error = '') {
  successMessage.value = message
  errorMessage.value = error
}

function navigate(route) {
  activeRoute.value = resolveRoute(route)
  window.location.hash = activeRoute.value
}

function handleHashChange() {
  activeRoute.value = resolveRoute(window.location.hash)
}

async function restoreSession() {
  try {
    currentUser.value = await fetchCurrentUser()
  } catch (error) {
    currentUser.value = null
    if (error.status !== 401) {
      setFeedback('', error.message)
    }
  } finally {
    authChecked.value = true
  }
}

async function handleLogin() {
  isSubmitting.value = true
  activeSubmit.value = 'login'
  setFeedback()

  try {
    currentUser.value = await login({
      loginId: loginForm.loginId.trim(),
      password: loginForm.password,
      rememberDevice: loginForm.rememberDevice,
    })
    navigate('launcher')
    setFeedback('로그인되었습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleLogout() {
  try {
    await logoutRequest()
  } catch {
    // 로그아웃 요청이 실패해도 화면 상태는 바로 정리합니다.
  }

  currentUser.value = null
  navigate('launcher')
  setFeedback('로그아웃되었습니다.')
}

onMounted(() => {
  window.addEventListener('hashchange', handleHashChange)
  restoreSession()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', handleHashChange)
})
</script>

<template>
  <div class="app-shell">
    <div v-if="!authChecked" class="loading-overlay">세션을 확인하는 중입니다...</div>

    <template v-else-if="!currentUser">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">로그인 후 기능 선택</span>
          <h1>로그인하면 가계부, 여행, 가족 앨범 기능을 바로 사용할 수 있습니다.</h1>
          <p>회원가입은 비활성화되어 있으며, 새 계정은 개발자 또는 관리자만 직접 추가할 수 있습니다.</p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>로그인</h2>
            <form class="stack-form" @submit.prevent="handleLogin">
              <input v-model="loginForm.loginId" type="text" placeholder="아이디" autocomplete="username" />
              <input v-model="loginForm.password" type="password" placeholder="비밀번호" autocomplete="current-password" />
              <label class="checkbox-row">
                <input v-model="loginForm.rememberDevice" type="checkbox" />
                <span>이 기기에서 로그인 유지</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'login' ? '로그인 중...' : '로그인' }}
              </button>
            </form>
          </article>

          <article class="auth-card">
            <h2>계정 안내</h2>
            <div class="stack-form stack-form--readonly">
              <p>회원가입 기능은 제공하지 않습니다.</p>
              <p>새 계정이 필요하면 개발자에게 직접 사용자 정보를 추가해 달라고 요청해 주세요.</p>
            </div>
          </article>
        </div>
      </section>

      <div v-if="successMessage" class="feedback feedback--success auth-feedback">{{ successMessage }}</div>
      <div v-if="errorMessage" class="feedback feedback--error auth-feedback">{{ errorMessage }}</div>
    </template>

    <template v-else>
      <div class="main-shell main-shell--standalone">
        <header class="topbar">
          <div>
            <p class="topbar__eyebrow">{{ pageMeta.title }}</p>
            <h1>{{ pageMeta.description }}</h1>
          </div>
          <div class="topbar__actions">
            <button v-if="activeRoute !== 'launcher'" class="button button--ghost" @click="navigate('launcher')">기능 선택으로</button>
            <button class="button button--ghost" @click="handleLogout">로그아웃</button>
          </div>
        </header>

        <div v-if="successMessage" class="feedback feedback--success">{{ successMessage }}</div>
        <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

        <FeatureLauncher
          v-if="activeRoute === 'launcher'"
          :current-user="currentUser"
          :items="featureItems"
          @navigate="navigate"
        />
        <HouseholdWorkspace v-else-if="activeRoute === 'household'" />
        <FamilyAlbumWorkspace v-else-if="activeRoute === 'family-album'" />
        <TravelHubWorkspace v-else :route="activeRoute" />
      </div>
    </template>
  </div>
</template>

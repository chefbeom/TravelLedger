<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import FeatureLauncher from './components/FeatureLauncher.vue'
import HouseholdWorkspace from './components/HouseholdWorkspace.vue'
import TravelHubWorkspace from './components/TravelHubWorkspace.vue'
import { fetchCurrentUser, login, logout as logoutRequest, register } from './lib/api'

const featureItems = [
  {
    key: 'household',
    number: '1',
    title: '가계부',
    description: '예전에 만들었던 가계부 기능 전체를 다시 사용하는 페이지입니다.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: '여행 돈 장부',
    description: '여행 생성, 예산안, 지출 장부, 지출 통계를 관리합니다.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: '여행 로그',
    description: '날짜별 여행 기록, 지도 핀, 이동 경로와 GPX를 남깁니다.',
  },
  {
    key: 'photo-album',
    number: '4',
    title: '사진첩',
    description: '여행 사진을 재사용하고 지도 갤러리와 커뮤니티 피드를 확인합니다.',
  },
]

const routeMeta = {
  launcher: {
    title: '기능 선택',
    description: '원하는 기능 페이지를 하나 선택해서 들어가세요.',
  },
  household: {
    title: '가계부',
    description: '캘린더, 통계, 검색, 관리 기능을 포함한 가계부 페이지입니다.',
  },
  'travel-money': {
    title: '여행 돈 장부',
    description: '여행 예산안과 지출 기록을 관리하는 독립 페이지입니다.',
  },
  'travel-log': {
    title: '여행 로그',
    description: '여행 기록과 이동 경로를 남기는 독립 페이지입니다.',
  },
  'photo-album': {
    title: '사진첩',
    description: '여행 사진 재사용과 지도형 사진첩을 보는 독립 페이지입니다.',
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

const registerForm = reactive({
  displayName: '',
  loginId: '',
  password: '',
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

async function handleRegister() {
  isSubmitting.value = true
  activeSubmit.value = 'register'
  setFeedback()

  try {
    currentUser.value = await register({
      displayName: registerForm.displayName.trim(),
      loginId: registerForm.loginId.trim(),
      password: registerForm.password,
      rememberDevice: registerForm.rememberDevice,
    })
    registerForm.displayName = ''
    registerForm.loginId = ''
    registerForm.password = ''
    navigate('launcher')
    setFeedback('회원가입이 완료되었고 바로 로그인되었습니다.')
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
    // 서버 로그아웃 요청이 실패해도 클라이언트 상태는 정리합니다.
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
          <h1>로그인하면 먼저 1번부터 4번까지 기능을 고르는 페이지가 열립니다.</h1>
          <p>가계부, 여행 돈 장부, 여행 로그, 사진첩은 서로 섞이지 않고 각각 독립된 페이지로 들어가게 구성합니다.</p>
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
            <h2>회원가입</h2>
            <form class="stack-form" @submit.prevent="handleRegister">
              <input v-model="registerForm.displayName" type="text" placeholder="표시 이름" autocomplete="name" />
              <input v-model="registerForm.loginId" type="text" placeholder="아이디" autocomplete="username" />
              <input v-model="registerForm.password" type="password" placeholder="비밀번호" autocomplete="new-password" />
              <label class="checkbox-row">
                <input v-model="registerForm.rememberDevice" type="checkbox" />
                <span>이 기기에서 로그인 유지</span>
              </label>
              <button class="button" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'register' ? '가입 중...' : '계정 만들기' }}
              </button>
            </form>
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

        <FeatureLauncher v-if="activeRoute === 'launcher'" :current-user="currentUser" :items="featureItems" @navigate="navigate" />
        <HouseholdWorkspace v-else-if="activeRoute === 'household'" />
        <TravelHubWorkspace v-else :route="activeRoute" />
      </div>
    </template>
  </div>
</template>
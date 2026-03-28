<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import FeatureLauncher from './components/FeatureLauncher.vue'
import FamilyAlbumWorkspace from './components/FamilyAlbumWorkspace.vue'
import HouseholdWorkspace from './components/HouseholdWorkspace.vue'
import InviteAccessPanel from './components/InviteAccessPanel.vue'
import TravelHubWorkspace from './components/TravelHubWorkspace.vue'
import {
  acceptInvite,
  createInvite,
  fetchCurrentUser,
  fetchInvite,
  login,
  logout as logoutRequest,
} from './lib/api'

const featureItems = [
  {
    key: 'household',
    number: '1',
    title: 'Ledger',
    description: 'Open the household ledger, charts, search, and management tools from one screen.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: 'Travel Budget',
    description: 'Manage travel plans, budget items, actual spending, and summaries together.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: 'Travel Log',
    description: 'Organize travel records, routes, photos, and GPX files in one workspace.',
  },
  {
    key: 'photo-album',
    number: '4',
    title: 'Travel Photos',
    description: 'Browse travel memories and uploaded photos in a dedicated gallery view.',
  },
  {
    key: 'family-album',
    number: '5',
    title: 'Family Album',
    description: 'Share photos and videos with invited family members by category.',
  },
]

const routeMeta = {
  launcher: {
    title: 'Workspace Launcher',
    description: 'Choose the area you want to open next.',
  },
  household: {
    title: 'Household Ledger',
    description: 'Check the calendar ledger, statistics, search, and category management together.',
  },
  'travel-money': {
    title: 'Travel Budget',
    description: 'Track the travel budget plan and actual spending in one place.',
  },
  'travel-log': {
    title: 'Travel Log',
    description: 'Review travel notes, routes, places, and uploaded files.',
  },
  'photo-album': {
    title: 'Travel Photos',
    description: 'Browse the photo-focused travel view built from your records.',
  },
  'family-album': {
    title: 'Family Album',
    description: 'Share family categories, albums, and media with invited members.',
  },
  invite: {
    title: 'Invite Signup',
    description: 'A new account can be created only through a one-time invite link.',
  },
}

const initialRouteState = resolveRouteState(window.location.hash)

const authChecked = ref(false)
const currentUser = ref(null)
const isSubmitting = ref(false)
const activeSubmit = ref('')
const successMessage = ref('')
const errorMessage = ref('')
const activeRoute = ref(initialRouteState.route)
const inviteToken = ref(initialRouteState.token)
const inviteInfo = ref(null)
const isInviteLoading = ref(false)
const isCreatingInvite = ref(false)

const loginForm = reactive({
  loginId: '',
  password: '',
  rememberDevice: true,
})

const inviteForm = reactive({
  loginId: '',
  displayName: '',
  password: '',
  rememberDevice: true,
})

const inviteManager = reactive({
  expiresInHours: 72,
  generatedLink: '',
  generatedExpiresAt: '',
  feedbackMessage: '',
  errorMessage: '',
})

const pageMeta = computed(() => routeMeta[activeRoute.value] || routeMeta.launcher)

let inviteRequestSequence = 0

function resolveRouteState(hash) {
  const route = String(hash || '').replace(/^#/, '').trim()
  if (route.toLowerCase().startsWith('invite/')) {
    return {
      route: 'invite',
      token: decodeURIComponent(route.slice('invite/'.length)).trim(),
    }
  }

  return {
    route: routeMeta[route] ? route : 'launcher',
    token: '',
  }
}

function formatDateTime(value) {
  if (!value) {
    return '-'
  }

  const normalized = new Date(value)
  if (Number.isNaN(normalized.getTime())) {
    return String(value)
  }

  return new Intl.DateTimeFormat('en-US', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(normalized)
}

function setFeedback(message = '', error = '') {
  successMessage.value = message
  errorMessage.value = error
}

function clearInviteManagerFeedback() {
  inviteManager.feedbackMessage = ''
  inviteManager.errorMessage = ''
}

function applyHashRoute(hash) {
  const routeState = resolveRouteState(hash)
  activeRoute.value = routeState.route
  inviteToken.value = routeState.token
}

function buildInviteUrl(token) {
  const path = window.location.pathname || '/'
  return `${window.location.origin}${path}#invite/${encodeURIComponent(token)}`
}

function navigate(route) {
  const nextRoute = routeMeta[route] ? route : 'launcher'
  activeRoute.value = nextRoute
  inviteToken.value = ''
  window.location.hash = nextRoute
}

function handleHashChange() {
  applyHashRoute(window.location.hash)
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

async function loadInviteDetails(token) {
  const requestId = ++inviteRequestSequence

  if (!token) {
    inviteInfo.value = null
    isInviteLoading.value = false
    setFeedback('', 'This invite link is not valid.')
    return
  }

  isInviteLoading.value = true
  inviteInfo.value = null
  setFeedback()

  try {
    const response = await fetchInvite(token)
    if (requestId !== inviteRequestSequence) {
      return
    }
    inviteInfo.value = response
  } catch (error) {
    if (requestId !== inviteRequestSequence) {
      return
    }
    inviteInfo.value = null
    setFeedback('', error.message)
  } finally {
    if (requestId === inviteRequestSequence) {
      isInviteLoading.value = false
    }
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
    setFeedback('You are now signed in.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleAcceptInvite() {
  if (!inviteInfo.value || !inviteToken.value) {
    setFeedback('', 'Please open a valid invite link first.')
    return
  }

  isSubmitting.value = true
  activeSubmit.value = 'invite'
  setFeedback()

  const normalizedLoginId = inviteForm.loginId.trim()
  const normalizedDisplayName = inviteForm.displayName.trim()

  try {
    await acceptInvite({
      token: inviteToken.value,
      loginId: normalizedLoginId,
      displayName: normalizedDisplayName,
      password: inviteForm.password,
    })

    currentUser.value = await login({
      loginId: normalizedLoginId,
      password: inviteForm.password,
      rememberDevice: inviteForm.rememberDevice,
    })

    inviteForm.loginId = ''
    inviteForm.displayName = ''
    inviteForm.password = ''
    navigate('launcher')
    setFeedback('The invited account was created and signed in.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleCreateInvite() {
  isCreatingInvite.value = true
  clearInviteManagerFeedback()

  try {
    const response = await createInvite({
      expiresInHours: inviteManager.expiresInHours,
    })

    inviteManager.generatedLink = buildInviteUrl(response.token)
    inviteManager.generatedExpiresAt = response.expiresAt
    inviteManager.feedbackMessage = 'A one-time invite link is ready to share.'
  } catch (error) {
    inviteManager.errorMessage = error.message
  } finally {
    isCreatingInvite.value = false
  }
}

async function copyInviteLink() {
  if (!inviteManager.generatedLink) {
    return
  }

  clearInviteManagerFeedback()

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(inviteManager.generatedLink)
    } else {
      const element = document.createElement('textarea')
      element.value = inviteManager.generatedLink
      element.setAttribute('readonly', 'readonly')
      element.style.position = 'absolute'
      element.style.left = '-9999px'
      document.body.appendChild(element)
      element.select()
      document.execCommand('copy')
      element.remove()
    }

    inviteManager.feedbackMessage = 'The invite link has been copied to the clipboard.'
  } catch {
    inviteManager.errorMessage = 'Automatic copy is not available in this browser. Please copy the link manually.'
  }
}

async function handleLogout() {
  try {
    await logoutRequest()
  } catch {
    // Keep the UI consistent even when the logout request fails.
  }

  currentUser.value = null
  navigate('launcher')
  setFeedback('You have been signed out.')
}

watch([activeRoute, inviteToken], ([route, token]) => {
  if (route === 'invite') {
    loadInviteDetails(token)
    return
  }

  inviteRequestSequence += 1
  inviteInfo.value = null
  isInviteLoading.value = false
}, { immediate: true })

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
    <div v-if="!authChecked" class="loading-overlay">Checking your session...</div>

    <template v-else-if="activeRoute === 'invite'">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">Invite Signup</span>
          <h1>New accounts can be created only through a one-time invite link.</h1>
          <p>If the link is valid, enter a login ID, display name, and password to create the account and sign in immediately.</p>
          <p v-if="currentUser" class="auth-copy__hint">
            You are currently signed in as {{ currentUser.displayName }} ({{ currentUser.loginId }}). After signup, this browser will switch to the new account.
          </p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>Invite status</h2>
            <div class="stack-form stack-form--readonly">
              <p v-if="isInviteLoading">Checking the invite link...</p>
              <template v-else-if="inviteInfo">
                <p><strong>{{ inviteInfo.inviterDisplayName }}</strong> created this invite.</p>
                <p>Expires at: {{ formatDateTime(inviteInfo.expiresAt) }}</p>
              </template>
              <p v-else>This link cannot be used to create an account.</p>
            </div>
          </article>

          <article class="auth-card">
            <h2>Create an invited account</h2>
            <form class="stack-form" @submit.prevent="handleAcceptInvite">
              <input
                v-model="inviteForm.loginId"
                type="text"
                placeholder="Login ID"
                autocomplete="username"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.displayName"
                type="text"
                placeholder="Display name"
                autocomplete="name"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.password"
                type="password"
                placeholder="Password (8+ characters)"
                autocomplete="new-password"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <label class="checkbox-row">
                <input
                  v-model="inviteForm.rememberDevice"
                  type="checkbox"
                  :disabled="isSubmitting || isInviteLoading || !inviteInfo"
                />
                <span>Keep this browser signed in</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting || isInviteLoading || !inviteInfo">
                {{ isSubmitting && activeSubmit === 'invite' ? 'Creating account...' : 'Create account and sign in' }}
              </button>
            </form>
          </article>
        </div>
      </section>

      <div v-if="successMessage" class="feedback feedback--success auth-feedback">{{ successMessage }}</div>
      <div v-if="errorMessage" class="feedback feedback--error auth-feedback">{{ errorMessage }}</div>
    </template>

    <template v-else-if="!currentUser">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">Sign in to continue</span>
          <h1>Sign in to use the ledger, travel, and family album workspaces.</h1>
          <p>Public signup is disabled. New accounts can be created only through a one-time invite link from an existing user.</p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>Sign in</h2>
            <form class="stack-form" @submit.prevent="handleLogin">
              <input v-model="loginForm.loginId" type="text" placeholder="Login ID" autocomplete="username" />
              <input v-model="loginForm.password" type="password" placeholder="Password" autocomplete="current-password" />
              <label class="checkbox-row">
                <input v-model="loginForm.rememberDevice" type="checkbox" />
                <span>Keep this browser signed in</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'login' ? 'Signing in...' : 'Sign in' }}
              </button>
            </form>
          </article>

          <article class="auth-card">
            <h2>Account access</h2>
            <div class="stack-form stack-form--readonly">
              <p>Open signup is turned off.</p>
              <p>Ask an existing user or administrator to create a one-time invite link for the next account.</p>
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
            <button v-if="activeRoute !== 'launcher'" class="button button--ghost" @click="navigate('launcher')">Back to launcher</button>
            <button class="button button--ghost" @click="handleLogout">Sign out</button>
          </div>
        </header>

        <div v-if="successMessage" class="feedback feedback--success">{{ successMessage }}</div>
        <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

        <div v-if="activeRoute === 'launcher'" class="workspace-stack">
          <FeatureLauncher
            :current-user="currentUser"
            :items="featureItems"
            @navigate="navigate"
          />
          <InviteAccessPanel
            :expires-in-hours="inviteManager.expiresInHours"
            :generated-link="inviteManager.generatedLink"
            :generated-expires-at="inviteManager.generatedExpiresAt"
            :is-creating="isCreatingInvite"
            :feedback-message="inviteManager.feedbackMessage"
            :error-message="inviteManager.errorMessage"
            @change-expiry="inviteManager.expiresInHours = $event"
            @create-invite="handleCreateInvite"
            @copy-invite="copyInviteLink"
          />
        </div>
        <HouseholdWorkspace v-else-if="activeRoute === 'household'" />
        <FamilyAlbumWorkspace v-else-if="activeRoute === 'family-album'" />
        <TravelHubWorkspace v-else :route="activeRoute" />
      </div>
    </template>
  </div>
</template>

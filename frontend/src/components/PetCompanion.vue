<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import yunaSpriteUrl from '../assets/pets/yuna-spritesheet.webp'
import momoImageUrl from '../assets/pets/momo-cat.webp'
import bomiImageUrl from '../assets/pets/bomi-dog.webp'

const PET_STORAGE_KEY = 'calen-pet-companion:v1'
const PET_MARGIN = 12

const props = defineProps({
  notification: {
    type: Object,
    default: null,
  },
})

const emit = defineEmits(['open-notifications'])

const PETS = [
  {
    id: 'yuna',
    name: 'Yuna',
    kind: 'sprite',
    asset: yunaSpriteUrl,
    description: '긴 흑발 일본 여고생 청춘 캐릭터. 차분하게 알림을 알려줍니다.',
    greeting: '오늘 알림은 제가 챙길게요.',
    petLine: '좋아요. 잠깐 쉬었다가 다시 이어가요.',
  },
  {
    id: 'momo',
    name: '모모',
    kind: 'image',
    asset: momoImageUrl,
    description: '흰 털 70%, 검은 무늬 30%의 반응 빠른 고양이 펫입니다.',
    greeting: '새 소식이 오면 바로 알려드릴게요.',
    petLine: '쓰다듬어 주셔서 기분이 좋아요.',
  },
  {
    id: 'bomi',
    name: '보미',
    kind: 'image',
    asset: bomiImageUrl,
    description: '친근한 강아지 알림 도우미입니다.',
    greeting: '알림을 놓치지 않게 도와드릴게요.',
    petLine: '좋아요. 다음 알림도 잘 지켜볼게요.',
  },
]

function clampPosition(value, min, max) {
  return Math.max(min, Math.min(max, Number(value) || min))
}

function createDefaultSettings() {
  return {
    enabled: true,
    selectedPetId: 'yuna',
    showSpeech: true,
    reactToNotifications: true,
    announceAloud: false,
    position: {
      x: null,
      y: null,
    },
  }
}

function loadSettings() {
  if (typeof window === 'undefined') {
    return createDefaultSettings()
  }

  try {
    const stored = JSON.parse(window.localStorage.getItem(PET_STORAGE_KEY) || '{}')
    const defaults = createDefaultSettings()
    return {
      ...defaults,
      ...stored,
      selectedPetId: PETS.some((pet) => pet.id === stored?.selectedPetId) ? stored.selectedPetId : defaults.selectedPetId,
      position: {
        ...defaults.position,
        ...(stored?.position && typeof stored.position === 'object' ? stored.position : {}),
      },
    }
  } catch {
    return createDefaultSettings()
  }
}

const settings = reactive(loadSettings())
const isQuickOpen = ref(false)
const isManagerOpen = ref(false)
const reaction = ref('idle')
const speechText = ref('')
const speechVisible = ref(false)
const petElement = ref(null)
const dragState = reactive({
  pointerId: null,
  startX: 0,
  startY: 0,
  originX: 0,
  originY: 0,
  moved: false,
})

let reactionTimer = null
let speechTimer = null

const currentPet = computed(() => PETS.find((pet) => pet.id === settings.selectedPetId) || PETS[0])
const petPositionStyle = computed(() => {
  const x = Number(settings.position.x)
  const y = Number(settings.position.y)

  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    return {}
  }

  return {
    left: `${x}px`,
    top: `${y}px`,
    right: 'auto',
    bottom: 'auto',
  }
})

function persistSettings() {
  if (typeof window === 'undefined') {
    return
  }

  window.localStorage.setItem(PET_STORAGE_KEY, JSON.stringify(settings))
}

function clearTimers() {
  if (reactionTimer) {
    window.clearTimeout(reactionTimer)
    reactionTimer = null
  }
  if (speechTimer) {
    window.clearTimeout(speechTimer)
    speechTimer = null
  }
}

function speakAloud(message) {
  if (!settings.announceAloud || typeof window === 'undefined' || !('speechSynthesis' in window)) {
    return
  }

  window.speechSynthesis.cancel()
  const utterance = new SpeechSynthesisUtterance(message)
  utterance.lang = 'ko-KR'
  utterance.rate = 1.04
  utterance.pitch = 1.04
  window.speechSynthesis.speak(utterance)
}

function react(nextReaction = 'happy', message = '', duration = 3600) {
  if (reactionTimer) {
    window.clearTimeout(reactionTimer)
  }

  reaction.value = nextReaction
  if (settings.showSpeech && message) {
    speechText.value = message
    speechVisible.value = true
    if (speechTimer) {
      window.clearTimeout(speechTimer)
    }
    speechTimer = window.setTimeout(() => {
      speechVisible.value = false
      speechTimer = null
    }, duration)
  }

  reactionTimer = window.setTimeout(() => {
    reaction.value = 'idle'
    reactionTimer = null
  }, Math.max(900, duration))
}

function handleAvatarClick() {
  if (dragState.moved) {
    dragState.moved = false
    return
  }

  isQuickOpen.value = !isQuickOpen.value
  if (isQuickOpen.value) {
    react('happy', currentPet.value.greeting, 3000)
  }
}

function startPetInteraction() {
  react('happy', currentPet.value.petLine)
}

function openNotificationCenter() {
  isQuickOpen.value = false
  emit('open-notifications')
}

function openManager() {
  isQuickOpen.value = false
  isManagerOpen.value = true
}

function closeManager() {
  isManagerOpen.value = false
}

function selectPet(id) {
  settings.selectedPetId = id
  settings.enabled = true
  react('happy', `${currentPet.value.name}으로 변경했어요.`, 2600)
}

function togglePetVisibility() {
  settings.enabled = !settings.enabled
  isQuickOpen.value = false
}

function resetPosition() {
  settings.position.x = null
  settings.position.y = null
}

function startDrag(event) {
  if (event.button !== undefined && event.button !== 0) {
    return
  }

  const element = petElement.value
  if (!element) {
    return
  }

  const rect = element.getBoundingClientRect()
  dragState.pointerId = event.pointerId
  dragState.startX = event.clientX
  dragState.startY = event.clientY
  dragState.originX = Number.isFinite(Number(settings.position.x)) ? Number(settings.position.x) : rect.left
  dragState.originY = Number.isFinite(Number(settings.position.y)) ? Number(settings.position.y) : rect.top
  dragState.moved = false
  element.setPointerCapture?.(event.pointerId)
}

function moveDrag(event) {
  if (dragState.pointerId !== event.pointerId || typeof window === 'undefined') {
    return
  }

  const deltaX = event.clientX - dragState.startX
  const deltaY = event.clientY - dragState.startY
  if (Math.abs(deltaX) + Math.abs(deltaY) > 5) {
    dragState.moved = true
  }
  if (!dragState.moved) {
    return
  }

  const width = petElement.value?.offsetWidth || 96
  const height = petElement.value?.offsetHeight || 108
  settings.position.x = clampPosition(dragState.originX + deltaX, PET_MARGIN, window.innerWidth - width - PET_MARGIN)
  settings.position.y = clampPosition(dragState.originY + deltaY, PET_MARGIN, window.innerHeight - height - PET_MARGIN)
}

function endDrag(event) {
  if (dragState.pointerId !== event.pointerId) {
    return
  }

  petElement.value?.releasePointerCapture?.(event.pointerId)
  dragState.pointerId = null
  persistSettings()
}

watch(settings, persistSettings, { deep: true })

watch(
  () => props.notification?.id,
  (id) => {
    if (!id || !settings.enabled || !settings.reactToNotifications) {
      return
    }

    const category = String(props.notification?.category || '알림').trim()
    const title = String(props.notification?.title || '새 알림').trim()
    const message = `${category} 알림이 도착했어요. ${title}`
    isQuickOpen.value = false
    react('alert', message, 5200)
    speakAloud(message)
  },
)

onBeforeUnmount(() => {
  clearTimers()
  if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
    window.speechSynthesis.cancel()
  }
})

defineExpose({ openManager })
</script>

<template>
  <div class="pet-companion">
    <div
      v-if="settings.enabled"
      ref="petElement"
      :class="['pet-companion__dock', `pet-companion__dock--${reaction}`, { 'is-dragging': dragState.pointerId !== null }]"
      :style="petPositionStyle"
      @pointerdown="startDrag"
      @pointermove="moveDrag"
      @pointerup="endDrag"
      @pointercancel="endDrag"
    >
      <div v-if="speechVisible" class="pet-companion__speech" role="status" aria-live="polite">
        {{ speechText }}
      </div>

      <div v-if="isQuickOpen" class="pet-companion__quick-actions">
        <button type="button" @click="startPetInteraction">쓰다듬기</button>
        <button type="button" @click="openNotificationCenter">알림 센터</button>
        <button type="button" @click="openManager">펫 관리</button>
        <button type="button" @click="togglePetVisibility">숨기기</button>
      </div>

      <button
        class="pet-companion__avatar"
        :aria-label="`${currentPet.name} 펫 메뉴 열기`"
        type="button"
        @click="handleAvatarClick"
      >
        <span
          v-if="currentPet.kind === 'sprite'"
          class="pet-companion__sprite"
          :style="{ backgroundImage: `url(${currentPet.asset})` }"
          aria-hidden="true"
        />
        <img v-else :src="currentPet.asset" :alt="`${currentPet.name} 펫`" draggable="false" />
        <span class="pet-companion__name">{{ currentPet.name }}</span>
      </button>
    </div>

    <div v-if="isManagerOpen" class="travel-modal pet-manager-modal" @keydown.esc="closeManager">
      <section class="travel-modal__dialog pet-manager-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="pet-manager-title">
        <header class="travel-modal__header">
          <div>
            <span class="panel__eyebrow">PET COMPANION</span>
            <h2 id="pet-manager-title">알림 펫 관리</h2>
          </div>
          <button class="button button--ghost" type="button" @click="closeManager">닫기</button>
        </header>

        <div class="pet-manager-modal__body">
          <section class="pet-manager-modal__pets" aria-label="펫 목록">
            <button
              v-for="pet in PETS"
              :key="pet.id"
              :class="['pet-manager-card', { 'is-active': currentPet.id === pet.id }]"
              type="button"
              @click="selectPet(pet.id)"
            >
              <span
                v-if="pet.kind === 'sprite'"
                class="pet-manager-card__sprite"
                :style="{ backgroundImage: `url(${pet.asset})` }"
                aria-hidden="true"
              />
              <img v-else :src="pet.asset" :alt="pet.name" />
              <span class="pet-manager-card__copy">
                <strong>{{ pet.name }}</strong>
                <small>{{ pet.description }}</small>
              </span>
              <span class="pet-manager-card__state">{{ currentPet.id === pet.id ? '선택됨' : '선택' }}</span>
            </button>
          </section>

          <section class="pet-manager-modal__settings" aria-label="펫 설정">
            <label class="pet-manager-toggle">
              <input v-model="settings.enabled" type="checkbox" />
              <span>펫 표시</span>
            </label>
            <label class="pet-manager-toggle">
              <input v-model="settings.reactToNotifications" type="checkbox" :disabled="!settings.enabled" />
              <span>새 알림 반응</span>
            </label>
            <label class="pet-manager-toggle">
              <input v-model="settings.showSpeech" type="checkbox" :disabled="!settings.enabled" />
              <span>말풍선 표시</span>
            </label>
            <label class="pet-manager-toggle">
              <input v-model="settings.announceAloud" type="checkbox" :disabled="!settings.enabled || !settings.reactToNotifications" />
              <span>브라우저 음성으로 알림 읽기</span>
            </label>
            <button class="button button--secondary" type="button" @click="resetPosition">화면 위치 초기화</button>
          </section>
        </div>
      </section>
    </div>
  </div>
</template>
<style scoped>
.pet-companion__dock { position: fixed; right: 18px; bottom: 18px; z-index: 1100; width: 100px; touch-action: none; user-select: none; }
.pet-companion__dock.is-dragging { cursor: grabbing; }
.pet-companion__avatar { position: relative; display: grid; width: 100px; height: 114px; padding: 0; border: 0; background: transparent; cursor: grab; place-items: center; }
.pet-companion__avatar img, .pet-companion__sprite { display: block; width: 96px; height: 96px; object-fit: contain; filter: drop-shadow(0 5px 9px rgba(0,0,0,.3)); }
.pet-companion__sprite { background-repeat: no-repeat; background-position: 0 0; background-size: 800% 1100%; image-rendering: pixelated; }
.pet-companion__name { position: absolute; bottom: 0; padding: 3px 8px; border: 1px solid color-mix(in srgb, var(--accent, #21b891) 55%, transparent); border-radius: 999px; background: color-mix(in srgb, var(--panel, #162131) 92%, transparent); color: var(--text, #f7fbff); font-size: 12px; font-weight: 800; white-space: nowrap; }
.pet-companion__dock--idle .pet-companion__avatar img, .pet-companion__dock--idle .pet-companion__sprite { animation: pet-float 2.9s ease-in-out infinite; }
.pet-companion__dock--happy .pet-companion__avatar { animation: pet-happy .55s ease-in-out 3; }
.pet-companion__dock--alert .pet-companion__avatar { animation: pet-alert .42s ease-in-out 5; }
.pet-companion__speech { position: absolute; right: 0; bottom: 118px; width: min(255px, calc(100vw - 28px)); margin: 0; padding: 10px 12px; border: 1px solid color-mix(in srgb, var(--accent, #21b891) 60%, transparent); border-radius: 12px; background: var(--panel, #162131); box-shadow: 0 11px 30px rgba(0,0,0,.25); color: var(--text, #f7fbff); font-size: 13px; line-height: 1.45; }
.pet-companion__quick-actions { position: absolute; right: 0; bottom: 120px; display: grid; gap: 5px; width: 132px; padding: 7px; border: 1px solid var(--panel-border, #34455f); border-radius: 10px; background: var(--panel, #162131); box-shadow: 0 12px 30px rgba(0,0,0,.24); }
.pet-companion__quick-actions button, .pet-manager-modal button { min-height: 34px; border: 1px solid var(--panel-border, #34455f); border-radius: 7px; background: var(--input-bg, #111c2b); color: var(--text, #f7fbff); font: inherit; font-weight: 750; cursor: pointer; }
.pet-companion__quick-actions button:hover, .pet-manager-modal button:hover { border-color: var(--accent, #21b891); }
.pet-manager-modal { z-index: 1360; }
.pet-manager-modal__dialog { width: min(900px, calc(100vw - 32px)); max-height: min(760px, calc(100dvh - 32px)); overflow: auto; }
.pet-manager-modal__body { display: grid; grid-template-columns: minmax(0, 1fr) 250px; gap: 16px; padding: 18px; }
.pet-manager-modal__pets { display: grid; gap: 9px; }
.pet-manager-card { display: grid; grid-template-columns: 64px minmax(0, 1fr) auto; align-items: center; gap: 11px; min-height: 78px; padding: 8px; text-align: left; }
.pet-manager-card.is-active { border-color: var(--accent, #21b891); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent, #21b891) 45%, transparent); }
.pet-manager-card img, .pet-manager-card__sprite { width: 58px; height: 58px; object-fit: contain; background-repeat: no-repeat; background-position: 0 0; background-size: 800% 1100%; image-rendering: pixelated; }
.pet-manager-card__copy { min-width: 0; }
.pet-manager-card__copy strong, .pet-manager-card__copy small { display: block; }
.pet-manager-card__copy small { margin-top: 3px; color: var(--muted, #a4b4c7); line-height: 1.35; }
.pet-manager-card__state { color: var(--accent, #21b891); font-size: 12px; white-space: nowrap; }
.pet-manager-modal__settings { display: grid; align-content: start; gap: 10px; padding: 14px; border: 1px solid var(--panel-border, #34455f); border-radius: 9px; background: color-mix(in srgb, var(--panel, #162131) 80%, var(--accent, #21b891) 5%); }
.pet-manager-toggle { display: flex; gap: 9px; align-items: center; font-weight: 750; }
.pet-manager-toggle input { accent-color: var(--accent, #21b891); }
.pet-manager-modal__settings .button { margin-top: 8px; }
@keyframes pet-float { 0%,100% { transform: translateY(0) } 50% { transform: translateY(-7px) } }
@keyframes pet-happy { 0%,100% { transform: rotate(0) scale(1) } 40% { transform: rotate(-8deg) scale(1.08) } 70% { transform: rotate(8deg) scale(1.08) } }
@keyframes pet-alert { 0%,100% { transform: translateX(0) } 35% { transform: translateX(-5px) } 70% { transform: translateX(5px) } }
@media (max-width: 640px) {
  .pet-companion__dock { right: 8px; bottom: calc(8px + env(safe-area-inset-bottom)); transform: scale(.9); transform-origin: bottom right; }
  .pet-manager-modal__dialog { width: calc(100vw - 16px); max-height: calc(100dvh - 16px); }
  .pet-manager-modal__body { grid-template-columns: 1fr; padding: 14px; }
  .pet-manager-card { grid-template-columns: 52px minmax(0, 1fr) auto; min-height: 68px; }
  .pet-manager-card img, .pet-manager-card__sprite { width: 48px; height: 48px; }
}
@media (prefers-reduced-motion: reduce) {
  .pet-companion__dock, .pet-companion__dock * { animation: none !important; }
}
</style>

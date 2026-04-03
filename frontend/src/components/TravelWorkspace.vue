<script setup>
import { computed, ref, watch } from 'vue'
import TravelHubWorkspace from './TravelHubWorkspace.vue'
import TravelMyMapWorkspace from './TravelMyMapWorkspace.vue'

const props = defineProps({
  route: {
    type: String,
    default: 'travel',
  },
})

const primaryTab = ref('finance')
const atlasTab = ref('map')
const hubRoute = ref('travel-money')

function applyRouteState(route) {
  switch (route) {
    case 'travel-log':
      primaryTab.value = 'log'
      hubRoute.value = 'travel-log'
      break
    case 'photo-album':
      primaryTab.value = 'atlas'
      atlasTab.value = 'album'
      hubRoute.value = 'photo-album'
      break
    case 'my-map':
      primaryTab.value = 'atlas'
      atlasTab.value = 'map'
      break
    case 'travel-money':
    case 'travel':
    default:
      primaryTab.value = 'finance'
      hubRoute.value = 'travel-money'
      break
  }
}

function openFinance() {
  primaryTab.value = 'finance'
  hubRoute.value = 'travel-money'
}

function openLog() {
  primaryTab.value = 'log'
  hubRoute.value = 'travel-log'
}

function openAtlas(tab = 'map') {
  primaryTab.value = 'atlas'
  atlasTab.value = tab
  if (tab === 'album') {
    hubRoute.value = 'photo-album'
  }
}

function handleRequestOpenLog() {
  openLog()
}

function handleRequestOpenFinance() {
  openFinance()
}

const isHubVisible = computed(() => !(primaryTab.value === 'atlas' && atlasTab.value === 'map'))
const isIntegratedPhotoMode = computed(() => primaryTab.value === 'atlas' && atlasTab.value === 'album')

watch(
  () => props.route,
  (route) => {
    applyRouteState(route)
  },
  { immediate: true },
)
</script>

<template>
  <div class="workspace-stack travel-unified-shell">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>여행 기능 통합</h2>
          <p>여행 가계부, 여행 로그, 내 지도와 사진 기능을 한 흐름에서 정리해 사용할 수 있도록 다시 묶었습니다.</p>
        </div>
        <span class="panel__badge">여행 워크스페이스</span>
      </div>
      <div class="scope-toggle scope-toggle--wrap">
        <button class="button" :class="{ 'button--primary': primaryTab === 'finance' }" @click="openFinance">여행 가계부</button>
        <button class="button" :class="{ 'button--primary': primaryTab === 'log' }" @click="openLog">여행 로그</button>
        <button class="button" :class="{ 'button--primary': primaryTab === 'atlas' }" @click="openAtlas(atlasTab)">내 지도·사진</button>
      </div>
      <small class="field__hint">여행 설정은 가장 앞에 두고, 기존 완성도가 높은 여행 로그와 내 지도는 최대한 그대로 유지한 채 통합했습니다.</small>
    </section>

    <section v-if="primaryTab === 'atlas'" class="panel">
      <div class="scope-toggle scope-toggle--wrap">
        <button class="button" :class="{ 'button--primary': atlasTab === 'map' }" @click="openAtlas('map')">내 지도</button>
        <button class="button" :class="{ 'button--primary': atlasTab === 'album' }" @click="openAtlas('album')">사진첩·전시</button>
      </div>
      <small class="field__hint">여행 로그에 기록한 사진은 내 지도 흐름 안에서 다시 모아 보고, 필요할 때만 여행 로그 편집으로 이어집니다.</small>
    </section>

    <div v-show="primaryTab === 'atlas' && atlasTab === 'map'" class="workspace-stack">
      <TravelMyMapWorkspace :active="primaryTab === 'atlas' && atlasTab === 'map'" />
    </div>

    <div v-show="isHubVisible" class="workspace-stack">
      <TravelHubWorkspace
        :route="hubRoute"
        :integrated-mode="true"
        :integrated-photo-mode="isIntegratedPhotoMode"
        @request-open-finance="handleRequestOpenFinance"
        @request-open-log="handleRequestOpenLog"
      />
    </div>
  </div>
</template>

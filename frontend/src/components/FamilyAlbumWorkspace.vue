<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { createFamilyAlbum, createFamilyCategory, fetchFamilyAlbumBootstrap, uploadFamilyMedia } from '../lib/api'

const timestampFormatter = new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

const isLoading = ref(false)
const isCreatingCategory = ref(false)
const isUploading = ref(false)
const isCreatingAlbum = ref(false)
const uploadInputKey = ref(0)
const successMessage = ref('')
const errorMessage = ref('')

const bootstrap = reactive({
  currentUserId: null,
  users: [],
  categories: [],
  mediaItems: [],
  albums: [],
})

const selectedCategoryId = ref('')
const selectedAlbumId = ref('')
const selectedMediaIds = ref([])

const categoryForm = reactive({
  name: '',
  description: '',
  memberUserIds: [],
})

const uploadForm = reactive({
  caption: '',
  files: [],
})

const albumForm = reactive({
  title: '',
  description: '',
})

const mediaById = computed(() => {
  return new Map((bootstrap.mediaItems ?? []).map((item) => [String(item.id), item]))
})

const otherUsers = computed(() => {
  return (bootstrap.users ?? []).filter((user) => user.id !== bootstrap.currentUserId)
})

const selectedCategory = computed(() => {
  return (bootstrap.categories ?? []).find((category) => String(category.id) === String(selectedCategoryId.value)) ?? null
})

const categoryMediaItems = computed(() => {
  if (!selectedCategoryId.value) {
    return bootstrap.mediaItems ?? []
  }

  return (bootstrap.mediaItems ?? []).filter((item) => String(item.categoryId) === String(selectedCategoryId.value))
})

const selectedAlbum = computed(() => {
  return (bootstrap.albums ?? []).find((album) => String(album.id) === String(selectedAlbumId.value)) ?? null
})

const categoryAlbums = computed(() => {
  if (!selectedCategoryId.value) {
    return bootstrap.albums ?? []
  }

  return (bootstrap.albums ?? []).filter((album) => String(album.categoryId) === String(selectedCategoryId.value))
})

const selectedAlbumMedia = computed(() => {
  if (!selectedAlbum.value) {
    return []
  }

  return (selectedAlbum.value.mediaIds ?? [])
    .map((mediaId) => mediaById.value.get(String(mediaId)))
    .filter(Boolean)
})

const selectedMediaCount = computed(() => selectedMediaIds.value.length)

const totalVideoCount = computed(() => {
  return (bootstrap.mediaItems ?? []).filter((item) => item.mediaType === 'VIDEO').length
})

const totalPhotoCount = computed(() => {
  return (bootstrap.mediaItems ?? []).filter((item) => item.mediaType === 'PHOTO').length
})

watch(
  () => bootstrap.categories,
  (categories) => {
    if (!categories.length) {
      selectedCategoryId.value = ''
      return
    }

    const hasSelectedCategory = categories.some((category) => String(category.id) === String(selectedCategoryId.value))
    if (!hasSelectedCategory) {
      selectedCategoryId.value = String(categories[0].id)
    }
  },
  { deep: true, immediate: true },
)

watch(
  () => bootstrap.albums,
  (albums) => {
    if (!selectedAlbumId.value) {
      return
    }

    const hasSelectedAlbum = albums.some((album) => String(album.id) === String(selectedAlbumId.value))
    if (!hasSelectedAlbum) {
      selectedAlbumId.value = ''
    }
  },
  { deep: true },
)

watch(selectedCategoryId, () => {
  const validIds = new Set(categoryMediaItems.value.map((item) => String(item.id)))
  selectedMediaIds.value = selectedMediaIds.value.filter((mediaId) => validIds.has(String(mediaId)))
})

function setFeedback(success = '', error = '') {
  successMessage.value = success
  errorMessage.value = error
}

async function loadBootstrap(preferredCategoryId = selectedCategoryId.value, preferredAlbumId = selectedAlbumId.value) {
  isLoading.value = true
  setFeedback()

  try {
    const response = await fetchFamilyAlbumBootstrap()
    bootstrap.currentUserId = response.currentUserId
    bootstrap.users = response.users ?? []
    bootstrap.categories = response.categories ?? []
    bootstrap.mediaItems = response.mediaItems ?? []
    bootstrap.albums = response.albums ?? []

    const categoryMatch = bootstrap.categories.find((category) => String(category.id) === String(preferredCategoryId))
    selectedCategoryId.value = categoryMatch ? String(categoryMatch.id) : bootstrap.categories[0] ? String(bootstrap.categories[0].id) : ''

    const albumMatch = bootstrap.albums.find((album) => String(album.id) === String(preferredAlbumId))
    selectedAlbumId.value = albumMatch ? String(albumMatch.id) : ''
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isLoading.value = false
  }
}

function toggleMember(userId) {
  const key = String(userId)
  if (categoryForm.memberUserIds.includes(key)) {
    categoryForm.memberUserIds = categoryForm.memberUserIds.filter((item) => item !== key)
    return
  }

  categoryForm.memberUserIds = [...categoryForm.memberUserIds, key]
}

function handlePickFiles(event) {
  uploadForm.files = [...(event.target.files ?? [])]
}

function toggleMediaSelection(mediaId) {
  const key = String(mediaId)
  if (selectedMediaIds.value.includes(key)) {
    selectedMediaIds.value = selectedMediaIds.value.filter((item) => item !== key)
    return
  }

  selectedMediaIds.value = [...selectedMediaIds.value, key]
}

function clearAlbumViewer() {
  selectedAlbumId.value = ''
}

async function handleCreateCategory() {
  isCreatingCategory.value = true
  setFeedback()

  try {
    const created = await createFamilyCategory({
      name: categoryForm.name,
      description: categoryForm.description,
      memberUserIds: categoryForm.memberUserIds.map((item) => Number(item)),
    })

    categoryForm.name = ''
    categoryForm.description = ''
    categoryForm.memberUserIds = []
    await loadBootstrap(created.id, '')
    selectedCategoryId.value = String(created.id)
    setFeedback('가족 카테고리를 만들었습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isCreatingCategory.value = false
  }
}

async function handleUploadMedia() {
  if (!selectedCategoryId.value) {
    setFeedback('', '먼저 업로드할 가족 카테고리를 선택하세요.')
    return
  }

  isUploading.value = true
  setFeedback()

  try {
    await uploadFamilyMedia(Number(selectedCategoryId.value), uploadForm.files, uploadForm.caption)
    uploadForm.caption = ''
    uploadForm.files = []
    uploadInputKey.value += 1
    await loadBootstrap(selectedCategoryId.value, selectedAlbumId.value)
    setFeedback('사진 또는 동영상을 업로드했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isUploading.value = false
  }
}

async function handleCreateAlbum() {
  if (!selectedCategoryId.value) {
    setFeedback('', '먼저 가족 카테고리를 선택하세요.')
    return
  }

  isCreatingAlbum.value = true
  setFeedback()

  try {
    const created = await createFamilyAlbum({
      categoryId: Number(selectedCategoryId.value),
      title: albumForm.title,
      description: albumForm.description,
      mediaIds: selectedMediaIds.value.map((item) => Number(item)),
    })

    albumForm.title = ''
    albumForm.description = ''
    selectedMediaIds.value = []
    await loadBootstrap(selectedCategoryId.value, created.id)
    selectedAlbumId.value = String(created.id)
    setFeedback('선택한 사진과 동영상을 새 앨범으로 묶었습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isCreatingAlbum.value = false
  }
}

function openAlbum(albumId) {
  selectedAlbumId.value = String(albumId)
}

function isSelectedMedia(mediaId) {
  return selectedMediaIds.value.includes(String(mediaId))
}

function formatTimestamp(value) {
  if (!value) {
    return '-'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }

  return timestampFormatter.format(date)
}

onMounted(() => {
  loadBootstrap()
})
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>가족 사진첩</h2>
          <p>여행 사진 페이지와 분리된 독립 업로드 공간입니다. 가족 카테고리를 만들고, 구성원을 넣고, 사진과 동영상을 시간순으로 공유할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ bootstrap.categories.length }}개 카테고리</span>
      </div>
      <div class="family-album-summary-grid">
        <article class="summary-card family-album-summary-card">
          <span>전체 사진</span>
          <strong>{{ totalPhotoCount }}장</strong>
          <small>가족별로 공유되는 사진</small>
        </article>
        <article class="summary-card family-album-summary-card">
          <span>전체 동영상</span>
          <strong>{{ totalVideoCount }}개</strong>
          <small>같은 흐름으로 업로드됩니다</small>
        </article>
        <article class="summary-card family-album-summary-card">
          <span>앨범 묶음</span>
          <strong>{{ bootstrap.albums.length }}개</strong>
          <small>선택한 파일만 그룹화</small>
        </article>
      </div>
    </section>

    <div v-if="successMessage" class="feedback feedback--success">{{ successMessage }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>가족 카테고리</h2>
          <p>카테고리를 만들 때 구성원을 같이 넣고, 그 카테고리 안에서 사진과 동영상을 같이 봅니다.</p>
        </div>
        <span class="panel__badge">{{ selectedCategory ? `${selectedCategory.memberCount}명 구성원` : '카테고리 선택 전' }}</span>
      </div>

      <div v-if="bootstrap.categories.length" class="family-category-grid">
        <button
          v-for="category in bootstrap.categories"
          :key="category.id"
          type="button"
          class="family-category-card"
          :class="{ 'family-category-card--active': String(category.id) === String(selectedCategoryId) }"
          @click="selectedCategoryId = String(category.id)"
        >
          <strong>{{ category.name }}</strong>
          <small>{{ category.ownerName }} 가족</small>
          <span>{{ category.mediaCount }}개 파일 · {{ category.memberCount }}명</span>
        </button>
      </div>
      <p v-else class="panel__empty">아직 만든 가족 카테고리가 없습니다. 먼저 카테고리를 만들면 업로드를 시작할 수 있습니다.</p>
    </section>

    <div class="family-album-layout">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>새 가족 카테고리 만들기</h2>
            <p>이름을 정하고, 같이 볼 사용자를 체크하세요. 본인은 자동으로 포함됩니다.</p>
          </div>
        </div>

        <form class="stack-form" @submit.prevent="handleCreateCategory">
          <input v-model="categoryForm.name" type="text" placeholder="예: 부모님 가족, 형제 가족, 여름휴가 모임" />
          <textarea v-model="categoryForm.description" rows="4" placeholder="이 카테고리를 어떤 용도로 쓸지 적어두면 나중에 구분하기 편합니다." />
          <div class="family-member-picker">
            <button
              v-for="user in otherUsers"
              :key="user.id"
              type="button"
              class="family-member-chip"
              :class="{ 'family-member-chip--active': categoryForm.memberUserIds.includes(String(user.id)) }"
              @click="toggleMember(user.id)"
            >
              <strong>{{ user.displayName }}</strong>
              <small>@{{ user.loginId }}</small>
            </button>
          </div>
          <button class="button button--primary" type="submit" :disabled="isCreatingCategory">
            {{ isCreatingCategory ? '카테고리 생성 중...' : '가족 카테고리 만들기' }}
          </button>
        </form>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>사진·동영상 업로드</h2>
            <p>업로드한 파일은 선택한 가족 카테고리 안에서 기본 공유되며, 시간순으로 정렬됩니다.</p>
          </div>
          <span class="panel__badge">{{ selectedCategory ? selectedCategory.name : '카테고리 선택 필요' }}</span>
        </div>

        <form class="stack-form" @submit.prevent="handleUploadMedia">
          <select v-model="selectedCategoryId">
            <option value="" disabled>업로드할 가족 카테고리를 선택하세요</option>
            <option v-for="category in bootstrap.categories" :key="category.id" :value="String(category.id)">
              {{ category.name }}
            </option>
          </select>
          <textarea v-model="uploadForm.caption" rows="3" placeholder="파일에 공통으로 붙일 짧은 설명이 있으면 적어주세요." />
          <input
            :key="uploadInputKey"
            type="file"
            accept="image/*,video/*"
            multiple
            @change="handlePickFiles"
          />
          <div class="travel-file-chip-row">
            <span class="chip chip--neutral">기본 공유</span>
            <span class="chip chip--neutral">시간순 정렬</span>
            <span class="chip chip--neutral">사진·동영상 동시 업로드</span>
          </div>
          <button class="button button--primary" type="submit" :disabled="isUploading || !bootstrap.categories.length">
            {{ isUploading ? '업로드 중...' : '파일 업로드' }}
          </button>
        </form>
      </section>
    </div>

    <div class="family-album-layout family-album-layout--library">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>공유 파일 라이브러리</h2>
            <p>현재 선택한 가족 카테고리의 파일을 시간순으로 확인하고, 앨범에 넣을 파일을 바로 선택할 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ categoryMediaItems.length }}개 파일</span>
        </div>

        <div v-if="selectedAlbum" class="family-album-focus">
          <div class="family-album-focus__head">
            <div>
              <strong>{{ selectedAlbum.title }}</strong>
              <small>{{ selectedAlbum.description || '설명 없음' }}</small>
            </div>
            <button class="button button--ghost" type="button" @click="clearAlbumViewer">앨범 보기 닫기</button>
          </div>
          <div class="family-media-grid family-media-grid--compact">
            <article v-for="media in selectedAlbumMedia" :key="`album-${media.id}`" class="family-media-card family-media-card--album">
              <img v-if="media.mediaType === 'PHOTO'" :src="media.contentUrl" :alt="media.originalFileName" class="family-media-card__preview" />
              <video v-else class="family-media-card__preview" controls preload="metadata" playsinline>
                <source :src="media.contentUrl" :type="media.contentType" />
              </video>
              <div class="family-media-card__body">
                <strong>{{ media.originalFileName }}</strong>
                <small>{{ formatTimestamp(media.capturedAt || media.uploadedAt) }}</small>
              </div>
            </article>
          </div>
        </div>

        <div v-if="categoryMediaItems.length" class="family-media-grid">
          <article
            v-for="media in categoryMediaItems"
            :key="media.id"
            class="family-media-card"
            :class="{ 'family-media-card--selected': isSelectedMedia(media.id) }"
          >
            <button class="family-media-card__select" type="button" @click="toggleMediaSelection(media.id)">
              {{ isSelectedMedia(media.id) ? '선택됨' : '앨범에 넣기' }}
            </button>
            <img v-if="media.mediaType === 'PHOTO'" :src="media.contentUrl" :alt="media.originalFileName" class="family-media-card__preview" />
            <video v-else class="family-media-card__preview" controls preload="metadata" playsinline>
              <source :src="media.contentUrl" :type="media.contentType" />
            </video>
            <div class="family-media-card__body">
              <div class="travel-file-chip-row">
                <span class="chip chip--neutral">{{ media.mediaType === 'PHOTO' ? '사진' : '동영상' }}</span>
                <span class="chip chip--neutral">{{ media.ownerName }}</span>
              </div>
              <strong>{{ media.originalFileName }}</strong>
              <small>{{ media.caption || '기본 공유 파일' }}</small>
              <small>{{ formatTimestamp(media.capturedAt || media.uploadedAt) }}</small>
            </div>
          </article>
        </div>
        <p v-else-if="!isLoading" class="panel__empty">선택한 가족 카테고리에 아직 업로드된 파일이 없습니다.</p>
        <p v-else class="panel__empty">가족 사진첩을 불러오는 중입니다.</p>
      </section>

      <section class="panel family-album-sidebar">
        <div class="panel__header">
          <div>
            <h2>앨범 만들기</h2>
            <p>보고 싶은 사진과 동영상만 골라 한 앨범으로 묶어두면 한 번에 볼 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ selectedMediaCount }}개 선택</span>
        </div>

        <form class="stack-form" @submit.prevent="handleCreateAlbum">
          <input v-model="albumForm.title" type="text" placeholder="예: 2026 설날 가족 모임, 강릉 1박 2일" />
          <textarea v-model="albumForm.description" rows="4" placeholder="앨범 설명이나 같이 남길 메모를 적어주세요." />
          <button class="button button--primary" type="submit" :disabled="isCreatingAlbum || !selectedMediaCount">
            {{ isCreatingAlbum ? '앨범 생성 중...' : '선택 파일로 앨범 만들기' }}
          </button>
        </form>

        <div class="family-album-list">
          <button
            v-for="album in categoryAlbums"
            :key="album.id"
            type="button"
            class="family-album-list__item"
            :class="{ 'family-album-list__item--active': String(album.id) === String(selectedAlbumId) }"
            @click="openAlbum(album.id)"
          >
            <strong>{{ album.title }}</strong>
            <small>{{ album.itemCount }}개 파일 · {{ album.ownerName }}</small>
          </button>
        </div>
        <p v-if="!categoryAlbums.length" class="panel__empty">이 카테고리에는 아직 앨범이 없습니다.</p>
      </section>
    </div>
  </div>
</template>

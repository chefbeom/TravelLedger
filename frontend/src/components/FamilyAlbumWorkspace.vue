<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createFamilyAlbum,
  createFamilyCategory,
  fetchFamilyAlbumBootstrap,
  searchFamilyUsers,
  uploadFamilyMedia,
} from '../lib/api'
import { buildThumbnailUrl } from '../lib/mediaPreview'

const timestampFormatter = new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

const FAMILY_MEDIA_ACCEPT = '.jpg,.jpeg,.png,.webp,.gif,.bmp,.mp4,.m4v,.mov,.webm'
const DEFAULT_MEMBER_SEARCH_MESSAGE = '이름이나 로그인 ID를 2글자 이상 입력해 검색하세요.'

const isLoading = ref(false)
const isCreatingCategory = ref(false)
const isUploading = ref(false)
const isCreatingAlbum = ref(false)
const isSearchingUsers = ref(false)
const uploadInputKey = ref(0)
const successMessage = ref('')
const errorMessage = ref('')
const memberSearchQuery = ref('')
const memberSearchMessage = ref(DEFAULT_MEMBER_SEARCH_MESSAGE)
const memberSearchResults = ref([])
const selectedInvitees = ref([])

const bootstrap = reactive({
  currentUserId: null,
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
})

const uploadForm = reactive({
  caption: '',
  files: [],
})

const albumForm = reactive({
  title: '',
  description: '',
})

const mediaById = computed(() => new Map((bootstrap.mediaItems ?? []).map((item) => [String(item.id), item])))

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

const FAMILY_MEDIA_PAGE_SIZE = 10
const FAMILY_ALBUM_PAGE_SIZE = 10
const categoryMediaPage = ref(0)
const albumMediaPage = ref(0)
const categoryAlbumPage = ref(0)
const categoryMediaPageCount = computed(() => Math.max(Math.ceil(categoryMediaItems.value.length / FAMILY_MEDIA_PAGE_SIZE), 1))
const albumMediaPageCount = computed(() => Math.max(Math.ceil(selectedAlbumMedia.value.length / FAMILY_MEDIA_PAGE_SIZE), 1))
const categoryAlbumPageCount = computed(() => Math.max(Math.ceil(categoryAlbums.value.length / FAMILY_ALBUM_PAGE_SIZE), 1))
const pagedCategoryMediaItems = computed(() => {
  const start = categoryMediaPage.value * FAMILY_MEDIA_PAGE_SIZE
  return categoryMediaItems.value.slice(start, start + FAMILY_MEDIA_PAGE_SIZE)
})
const pagedSelectedAlbumMedia = computed(() => {
  const start = albumMediaPage.value * FAMILY_MEDIA_PAGE_SIZE
  return selectedAlbumMedia.value.slice(start, start + FAMILY_MEDIA_PAGE_SIZE)
})
const pagedCategoryAlbums = computed(() => {
  const start = categoryAlbumPage.value * FAMILY_ALBUM_PAGE_SIZE
  return categoryAlbums.value.slice(start, start + FAMILY_ALBUM_PAGE_SIZE)
})

const selectedMediaCount = computed(() => selectedMediaIds.value.length)
const selectedInviteeCount = computed(() => selectedInvitees.value.length)
const totalVideoCount = computed(() => (bootstrap.mediaItems ?? []).filter((item) => item.mediaType === 'VIDEO').length)
const totalPhotoCount = computed(() => (bootstrap.mediaItems ?? []).filter((item) => item.mediaType === 'PHOTO').length)

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
  categoryMediaPage.value = 0
  categoryAlbumPage.value = 0
  albumMediaPage.value = 0
})

watch(selectedAlbumId, () => {
  albumMediaPage.value = 0
})

watch(
  () => categoryMediaItems.value.length,
  () => {
    if (categoryMediaPage.value >= categoryMediaPageCount.value) {
      categoryMediaPage.value = Math.max(categoryMediaPageCount.value - 1, 0)
    }
  },
)

watch(
  () => selectedAlbumMedia.value.length,
  () => {
    if (albumMediaPage.value >= albumMediaPageCount.value) {
      albumMediaPage.value = Math.max(albumMediaPageCount.value - 1, 0)
    }
  },
)

watch(
  () => categoryAlbums.value.length,
  () => {
    if (categoryAlbumPage.value >= categoryAlbumPageCount.value) {
      categoryAlbumPage.value = Math.max(categoryAlbumPageCount.value - 1, 0)
    }
  },
)

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

async function handleSearchUsers() {
  const query = memberSearchQuery.value.trim()
  if (query.length < 2) {
    memberSearchResults.value = []
    memberSearchMessage.value = '검색어를 2글자 이상 입력해 주세요.'
    return
  }

  isSearchingUsers.value = true
  memberSearchMessage.value = '사용자를 검색하고 있습니다.'

  try {
    const results = await searchFamilyUsers(query)
    memberSearchResults.value = results
    memberSearchMessage.value = results.length
      ? `${results.length}명의 사용자를 찾았습니다.`
      : '검색 결과가 없습니다.'
  } catch (error) {
    memberSearchResults.value = []
    memberSearchMessage.value = error.message
  } finally {
    isSearchingUsers.value = false
  }
}

function isSelectedInvitee(userId) {
  return selectedInvitees.value.some((user) => String(user.id) === String(userId))
}

function toggleMember(user) {
  const key = String(user.id)
  if (isSelectedInvitee(key)) {
    selectedInvitees.value = selectedInvitees.value.filter((item) => String(item.id) !== key)
    return
  }

  selectedInvitees.value = [...selectedInvitees.value, user]
}

function resetMemberSearch() {
  memberSearchQuery.value = ''
  memberSearchResults.value = []
  selectedInvitees.value = []
  memberSearchMessage.value = DEFAULT_MEMBER_SEARCH_MESSAGE
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
      memberUserIds: selectedInvitees.value.map((item) => Number(item.id)),
    })

    categoryForm.name = ''
    categoryForm.description = ''
    resetMemberSearch()
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
    setFeedback('', '먼저 업로드할 가족 카테고리를 선택해 주세요.')
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
    setFeedback('', '먼저 가족 카테고리를 선택해 주세요.')
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
    setFeedback('선택한 파일로 앨범을 만들었습니다.')
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
          <p>가족별 카테고리를 만들고, 검색으로 멤버를 초대한 뒤 사진과 동영상을 안전하게 공유하세요.</p>
        </div>
        <span class="panel__badge">{{ bootstrap.categories.length }}개 카테고리</span>
      </div>
      <div class="family-album-summary-grid">
        <article class="summary-card family-album-summary-card">
          <span>전체 사진</span>
          <strong>{{ totalPhotoCount }}장</strong>
          <small>공유 가능한 사진 파일</small>
        </article>
        <article class="summary-card family-album-summary-card">
          <span>전체 동영상</span>
          <strong>{{ totalVideoCount }}개</strong>
          <small>허용된 형식만 업로드됩니다</small>
        </article>
        <article class="summary-card family-album-summary-card">
          <span>앨범 모음</span>
          <strong>{{ bootstrap.albums.length }}개</strong>
          <small>선택한 파일로 만든 묶음</small>
        </article>
      </div>
    </section>

    <div v-if="successMessage" class="feedback feedback--success">{{ successMessage }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>가족 카테고리</h2>
          <p>초대한 멤버만 접근할 수 있는 카테고리 단위로 파일을 나눠 관리합니다.</p>
        </div>
        <span class="panel__badge">{{ selectedCategory ? `${selectedCategory.memberCount}명 참여 중` : '카테고리 선택 필요' }}</span>
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
          <span>{{ category.mediaCount }}개 파일, {{ category.memberCount }}명</span>
        </button>
      </div>
      <p v-else class="panel__empty">아직 만든 가족 카테고리가 없습니다. 먼저 카테고리를 만든 뒤 업로드를 시작해 보세요.</p>
    </section>

    <div class="family-album-layout">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>새 가족 카테고리</h2>
            <p>멤버 전체 목록은 노출하지 않고, 검색으로 필요한 사용자만 찾아 초대할 수 있습니다.</p>
          </div>
        </div>

        <form class="stack-form" @submit.prevent="handleCreateCategory">
          <input v-model="categoryForm.name" type="text" placeholder="예: 부모님 가족사진, 주말 모임, 2026 설날" />
          <textarea v-model="categoryForm.description" rows="4" placeholder="카테고리 설명을 적어두면 나중에 구분하기 편합니다." />
          <div class="stack-form">
            <input
              v-model="memberSearchQuery"
              type="text"
              placeholder="이름 또는 로그인 ID로 검색"
              @keydown.enter.prevent="handleSearchUsers"
            />
            <button class="button button--ghost" type="button" :disabled="isSearchingUsers" @click="handleSearchUsers">
              {{ isSearchingUsers ? '검색 중' : '사용자 검색' }}
            </button>
            <small>{{ memberSearchMessage }}</small>
          </div>
          <div v-if="selectedInviteeCount" class="family-member-picker">
            <button
              v-for="user in selectedInvitees"
              :key="`selected-${user.id}`"
              type="button"
              class="family-member-chip family-member-chip--active"
              @click="toggleMember(user)"
            >
              <strong>{{ user.displayName }}</strong>
              <small>@{{ user.loginIdHint }}</small>
            </button>
          </div>
          <div v-if="memberSearchResults.length" class="family-member-picker">
            <button
              v-for="user in memberSearchResults"
              :key="user.id"
              type="button"
              class="family-member-chip"
              :class="{ 'family-member-chip--active': isSelectedInvitee(user.id) }"
              @click="toggleMember(user)"
            >
              <strong>{{ user.displayName }}</strong>
              <small>@{{ user.loginIdHint }}</small>
            </button>
          </div>
          <button class="button button--primary" type="submit" :disabled="isCreatingCategory">
            {{ isCreatingCategory ? '카테고리 생성 중' : '가족 카테고리 만들기' }}
          </button>
        </form>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>사진과 동영상 업로드</h2>
            <p>허용된 형식과 실제 파일 내용이 일치하는 경우에만 저장됩니다.</p>
          </div>
          <span class="panel__badge">{{ selectedCategory ? selectedCategory.name : '카테고리 선택 필요' }}</span>
        </div>

        <form class="stack-form" @submit.prevent="handleUploadMedia">
          <select v-model="selectedCategoryId">
            <option value="" disabled>업로드할 가족 카테고리를 선택해 주세요</option>
            <option v-for="category in bootstrap.categories" :key="category.id" :value="String(category.id)">
              {{ category.name }}
            </option>
          </select>
          <textarea v-model="uploadForm.caption" rows="3" placeholder="업로드 파일에 붙일 공통 설명을 입력해 주세요." />
          <input
            :key="uploadInputKey"
            type="file"
            :accept="FAMILY_MEDIA_ACCEPT"
            multiple
            @change="handlePickFiles"
          />
          <div class="travel-file-chip-row">
            <span class="chip chip--neutral">공유 기본값</span>
            <span class="chip chip--neutral">시간순 정렬</span>
            <span class="chip chip--neutral">허용 형식: JPG PNG WEBP GIF BMP MP4 M4V MOV WEBM</span>
          </div>
          <button class="button button--primary" type="submit" :disabled="isUploading || !bootstrap.categories.length">
            {{ isUploading ? '업로드 중' : '파일 업로드' }}
          </button>
        </form>
      </section>
    </div>

    <div class="family-album-layout family-album-layout--library">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>공유 파일 라이브러리</h2>
            <p>현재 카테고리의 파일을 확인하고, 앨범에 담을 항목을 바로 선택할 수 있습니다.</p>
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
            <article v-for="(media, index) in pagedSelectedAlbumMedia" :key="`album-${media.id}`" class="family-media-card family-media-card--album">
              <img
                v-if="media.mediaType === 'PHOTO'"
                :src="buildThumbnailUrl(media.contentUrl)"
                :alt="media.originalFileName"
                :loading="index < 4 ? 'eager' : 'lazy'"
                :fetchpriority="index < 4 ? 'high' : 'auto'"
                decoding="async"
                class="family-media-card__preview"
              />
              <video v-else class="family-media-card__preview" controls preload="metadata" playsinline>
                <source :src="media.contentUrl" :type="media.contentType" />
              </video>
              <div class="family-media-card__body">
                <strong>{{ media.originalFileName }}</strong>
                <small>{{ formatTimestamp(media.capturedAt || media.uploadedAt) }}</small>
              </div>
            </article>
          </div>
          <div v-if="selectedAlbumMedia.length > FAMILY_MEDIA_PAGE_SIZE" class="panel__actions">
            <button class="button button--ghost" type="button" :disabled="albumMediaPage <= 0" @click="albumMediaPage -= 1">이전</button>
            <span>{{ albumMediaPage + 1 }} / {{ albumMediaPageCount }}</span>
            <button class="button button--ghost" type="button" :disabled="albumMediaPage + 1 >= albumMediaPageCount" @click="albumMediaPage += 1">다음</button>
          </div>
        </div>

        <div v-if="categoryMediaItems.length" class="family-media-grid">
          <article
            v-for="(media, index) in pagedCategoryMediaItems"
            :key="media.id"
            class="family-media-card"
            :class="{ 'family-media-card--selected': isSelectedMedia(media.id) }"
          >
            <button class="family-media-card__select" type="button" @click="toggleMediaSelection(media.id)">
              {{ isSelectedMedia(media.id) ? '선택됨' : '앨범에 담기' }}
            </button>
            <img
              v-if="media.mediaType === 'PHOTO'"
              :src="buildThumbnailUrl(media.contentUrl)"
              :alt="media.originalFileName"
              :loading="index < 4 ? 'eager' : 'lazy'"
              :fetchpriority="index < 4 ? 'high' : 'auto'"
              decoding="async"
              class="family-media-card__preview"
            />
            <video v-else class="family-media-card__preview" controls preload="metadata" playsinline>
              <source :src="media.contentUrl" :type="media.contentType" />
            </video>
            <div class="family-media-card__body">
              <div class="travel-file-chip-row">
                <span class="chip chip--neutral">{{ media.mediaType === 'PHOTO' ? '사진' : '동영상' }}</span>
                <span class="chip chip--neutral">{{ media.ownerName }}</span>
              </div>
              <strong>{{ media.originalFileName }}</strong>
              <small>{{ media.caption || '공유 파일' }}</small>
              <small>{{ formatTimestamp(media.capturedAt || media.uploadedAt) }}</small>
            </div>
          </article>
        </div>
        <div v-if="categoryMediaItems.length > FAMILY_MEDIA_PAGE_SIZE" class="panel__actions">
          <button class="button button--ghost" type="button" :disabled="categoryMediaPage <= 0" @click="categoryMediaPage -= 1">이전</button>
          <span>{{ categoryMediaPage + 1 }} / {{ categoryMediaPageCount }}</span>
          <button class="button button--ghost" type="button" :disabled="categoryMediaPage + 1 >= categoryMediaPageCount" @click="categoryMediaPage += 1">다음</button>
        </div>
        <p v-else-if="!isLoading" class="panel__empty">선택한 가족 카테고리에 아직 업로드된 파일이 없습니다.</p>
        <p v-else class="panel__empty">가족 사진첩을 불러오는 중입니다.</p>
      </section>

      <section class="panel family-album-sidebar">
        <div class="panel__header">
          <div>
            <h2>앨범 만들기</h2>
            <p>보고 싶은 사진과 동영상만 골라 별도의 앨범으로 묶어둘 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ selectedMediaCount }}개 선택</span>
        </div>

        <form class="stack-form" @submit.prevent="handleCreateAlbum">
          <input v-model="albumForm.title" type="text" placeholder="예: 2026 설날 모임, 강릉 1박 2일" />
          <textarea v-model="albumForm.description" rows="4" placeholder="앨범 설명이나 메모를 적어 주세요." />
          <button class="button button--primary" type="submit" :disabled="isCreatingAlbum || !selectedMediaCount">
            {{ isCreatingAlbum ? '앨범 생성 중' : '선택 파일로 앨범 만들기' }}
          </button>
        </form>

        <div class="family-album-list">
          <button
            v-for="album in pagedCategoryAlbums"
            :key="album.id"
            type="button"
            class="family-album-list__item"
            :class="{ 'family-album-list__item--active': String(album.id) === String(selectedAlbumId) }"
            @click="openAlbum(album.id)"
          >
            <strong>{{ album.title }}</strong>
            <small>{{ album.itemCount }}개 파일, {{ album.ownerName }}</small>
          </button>
        </div>
        <div v-if="categoryAlbums.length > FAMILY_ALBUM_PAGE_SIZE" class="panel__actions">
          <button class="button button--ghost" type="button" :disabled="categoryAlbumPage <= 0" @click="categoryAlbumPage -= 1">이전</button>
          <span>{{ categoryAlbumPage + 1 }} / {{ categoryAlbumPageCount }}</span>
          <button class="button button--ghost" type="button" :disabled="categoryAlbumPage + 1 >= categoryAlbumPageCount" @click="categoryAlbumPage += 1">다음</button>
        </div>
        <p v-if="!categoryAlbums.length" class="panel__empty">이 카테고리에는 아직 앨범이 없습니다.</p>
      </section>
    </div>
  </div>
</template>

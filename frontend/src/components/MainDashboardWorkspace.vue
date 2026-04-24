<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchDashboard, fetchDriveHomeSummary, fetchTravelPortfolio } from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
  items: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['navigate'])

const loading = ref(false)
const errorMessage = ref('')
const householdDashboard = ref(null)
const travelPortfolio = ref(null)
const driveSummary = ref(null)

const featureCopy = {
  household: {
    title: '가계부',
    description: '월별 흐름, 최근 거래, 통계 화면으로 바로 이동합니다.',
    badge: 'Finance',
  },
  travel: {
    title: '여행',
    description: '여행 예산, 기록, 지도와 사진을 한 공간에서 이어서 관리합니다.',
    badge: 'Travel',
  },
  drive: {
    title: '드라이브',
    description: '파일, 폴더, 공유, 휴지통 상태를 확인하고 클라우드 공간으로 이동합니다.',
    badge: 'Cloud',
  },
  admin: {
    title: '관리자',
    description: '사용자, 초대, 접근 상태와 운영 도구를 확인합니다.',
    badge: 'Admin',
  },
}

function todayIso() {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

function formatCurrency(value) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
  }).format(Number(value ?? 0))
}

function formatNumber(value) {
  return new Intl.NumberFormat('ko-KR').format(Number(value ?? 0))
}

function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (!value) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  return `${(value / (1024 ** index)).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

function monthOverview() {
  return (householdDashboard.value?.quickStats ?? []).find((item) => item.key === 'month')?.overview ?? {}
}

const householdCards = computed(() => {
  const overview = monthOverview()
  return [
    { label: '이번 달 순액', value: formatCurrency(overview.balance), tone: Number(overview.balance ?? 0) >= 0 ? 'positive' : 'negative' },
    { label: '수입', value: formatCurrency(overview.income), tone: 'positive' },
    { label: '지출', value: formatCurrency(overview.expense), tone: 'negative' },
    { label: '거래', value: `${formatNumber(overview.entryCount)}건`, tone: 'neutral' },
  ]
})

const recentHouseholdEntries = computed(() => (householdDashboard.value?.recentEntries ?? []).slice(0, 4))

const travelPlans = computed(() => travelPortfolio.value?.plans ?? [])
const travelSummary = computed(() => {
  const plans = travelPlans.value
  const plannedTotal = plans.reduce((sum, plan) => sum + Number(plan.plannedTotalKrw ?? plan.totalBudgetKrw ?? 0), 0)
  const actualTotal = plans.reduce((sum, plan) => sum + Number(plan.actualTotalKrw ?? plan.totalExpenseKrw ?? 0), 0)
  const recordCount = plans.reduce((sum, plan) => sum + Number(plan.recordCount ?? plan.memoryRecordCount ?? 0), 0)
  const mediaCount = plans.reduce((sum, plan) => sum + Number(plan.mediaItemCount ?? 0), 0)
  return {
    planCount: plans.length,
    plannedTotal,
    actualTotal,
    recordCount,
    mediaCount,
  }
})

const recentTravelPlans = computed(() => travelPlans.value.slice(0, 4))

const driveCards = computed(() => [
  { label: '전체 항목', value: `${formatNumber(driveSummary.value?.driveItemCount)}개`, meta: `파일 ${formatNumber(driveSummary.value?.fileCount)}개 · 폴더 ${formatNumber(driveSummary.value?.folderCount)}개` },
  { label: '사용 용량', value: formatBytes(driveSummary.value?.usedBytes), meta: `휴지통 ${formatNumber(driveSummary.value?.trashCount)}개` },
  { label: '공유', value: `${formatNumber(driveSummary.value?.sharedCount)}건`, meta: '현재 공유된 파일' },
])

const recentDriveFiles = computed(() => (driveSummary.value?.recentFiles ?? []).slice(0, 4))

const featureItems = computed(() => props.items.map((item) => ({
  ...item,
  ...(featureCopy[item.key] ?? {}),
})))

async function loadSummaries() {
  loading.value = true
  errorMessage.value = ''
  const [householdResult, travelResult, driveResult] = await Promise.allSettled([
    fetchDashboard(todayIso()),
    fetchTravelPortfolio(),
    fetchDriveHomeSummary(),
  ])

  if (householdResult.status === 'fulfilled') {
    householdDashboard.value = householdResult.value
  }
  if (travelResult.status === 'fulfilled') {
    travelPortfolio.value = travelResult.value
  }
  if (driveResult.status === 'fulfilled') {
    driveSummary.value = driveResult.value
  }

  const failed = [householdResult, travelResult, driveResult].filter((result) => result.status === 'rejected')
  if (failed.length) {
    errorMessage.value = '일부 요약 정보를 불러오지 못했습니다. 각 기능 화면에서는 기존 기능을 그대로 사용할 수 있습니다.'
  }
  loading.value = false
}

onMounted(loadSummaries)
</script>

<template>
  <div class="main-dashboard">
    <section class="main-dashboard__header">
      <div>
        <span class="main-dashboard__eyebrow">통합 대시보드</span>
        <h2>{{ currentUser.displayName }}님의 오늘 흐름</h2>
        <p>가계부, 여행, 드라이브의 핵심 상태를 한 화면에서 확인합니다.</p>
      </div>
      <button class="button button--secondary" type="button" :disabled="loading" @click="loadSummaries">
        {{ loading ? '갱신 중' : '새로고침' }}
      </button>
    </section>

    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="main-dashboard__summary-grid">
      <article class="main-dashboard__section main-dashboard__section--wide">
        <div class="main-dashboard__section-head">
          <span>가계부</span>
          <button class="button button--ghost" type="button" @click="emit('navigate', 'household')">이동</button>
        </div>
        <div class="main-dashboard__metric-grid">
          <div v-for="card in householdCards" :key="card.label" class="main-dashboard__metric" :class="`is-${card.tone}`">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
          </div>
        </div>
        <div class="main-dashboard__list">
          <div v-for="entry in recentHouseholdEntries" :key="entry.id" class="main-dashboard__list-row">
            <span>{{ entry.title || '-' }}</span>
            <strong :class="entry.entryType === 'INCOME' ? 'is-positive' : 'is-negative'">{{ formatCurrency(entry.amount) }}</strong>
          </div>
          <p v-if="!recentHouseholdEntries.length" class="main-dashboard__empty">최근 거래가 없습니다.</p>
        </div>
      </article>

      <article class="main-dashboard__section">
        <div class="main-dashboard__section-head">
          <span>여행</span>
          <button class="button button--ghost" type="button" @click="emit('navigate', 'travel')">이동</button>
        </div>
        <div class="main-dashboard__travel-kpis">
          <div><span>여행</span><strong>{{ formatNumber(travelSummary.planCount) }}</strong></div>
          <div><span>기록</span><strong>{{ formatNumber(travelSummary.recordCount) }}</strong></div>
          <div><span>미디어</span><strong>{{ formatNumber(travelSummary.mediaCount) }}</strong></div>
        </div>
        <div class="main-dashboard__split-row">
          <span>예산</span>
          <strong>{{ formatCurrency(travelSummary.plannedTotal) }}</strong>
        </div>
        <div class="main-dashboard__split-row">
          <span>사용</span>
          <strong>{{ formatCurrency(travelSummary.actualTotal) }}</strong>
        </div>
        <div class="main-dashboard__list">
          <div v-for="plan in recentTravelPlans" :key="plan.id" class="main-dashboard__list-row">
            <span>{{ plan.name || plan.destination || '-' }}</span>
            <strong>{{ plan.status || 'PLANNED' }}</strong>
          </div>
          <p v-if="!recentTravelPlans.length" class="main-dashboard__empty">등록된 여행이 없습니다.</p>
        </div>
      </article>

      <article class="main-dashboard__section">
        <div class="main-dashboard__section-head">
          <span>드라이브</span>
          <button class="button button--ghost" type="button" @click="emit('navigate', 'drive')">이동</button>
        </div>
        <div class="main-dashboard__drive-grid">
          <div v-for="card in driveCards" :key="card.label" class="main-dashboard__drive-card">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.meta }}</small>
          </div>
        </div>
        <div class="main-dashboard__list">
          <div v-for="file in recentDriveFiles" :key="file.id" class="main-dashboard__list-row">
            <span>{{ file.fileOriginName || file.name || '-' }}</span>
            <strong>{{ formatBytes(file.fileSize) }}</strong>
          </div>
          <p v-if="!recentDriveFiles.length" class="main-dashboard__empty">최근 파일이 없습니다.</p>
        </div>
      </article>
    </section>

    <section class="main-dashboard__features">
      <article v-for="item in featureItems" :key="item.key" class="main-dashboard__feature">
        <span>{{ item.badge }}</span>
        <strong>{{ item.title }}</strong>
        <p>{{ item.description }}</p>
        <button class="button button--primary" type="button" @click="emit('navigate', item.key)">
          열기
        </button>
      </article>
    </section>
  </div>
</template>

<style scoped>
.main-dashboard {
  display: grid;
  gap: 18px;
  min-width: 0;
}

.main-dashboard__header {
  align-items: center;
  background: #ffffff;
  border: 1px solid #d9dde5;
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 18px;
}

.main-dashboard__eyebrow,
.main-dashboard__section-head span,
.main-dashboard__feature > span {
  color: #6f42c1;
  font-size: 0.74rem;
  font-weight: 800;
}

.main-dashboard__header h2 {
  color: #111827;
  font-size: 1.4rem;
  line-height: 1.2;
  margin: 4px 0 6px;
}

.main-dashboard__header p,
.main-dashboard__empty,
.main-dashboard__feature p {
  color: #6b7280;
  font-size: 0.86rem;
  margin: 0;
}

.main-dashboard__summary-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: minmax(0, 1.2fr) minmax(280px, 0.9fr);
}

.main-dashboard__section {
  background: #ffffff;
  border: 1px solid #d9dde5;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.04);
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px;
}

.main-dashboard__section--wide {
  grid-row: span 2;
}

.main-dashboard__section-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.main-dashboard__metric-grid,
.main-dashboard__drive-grid {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.main-dashboard__metric,
.main-dashboard__drive-card,
.main-dashboard__travel-kpis > div {
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px;
}

.main-dashboard__metric span,
.main-dashboard__drive-card span,
.main-dashboard__travel-kpis span,
.main-dashboard__split-row span {
  color: #6b7280;
  font-size: 0.74rem;
}

.main-dashboard__metric strong,
.main-dashboard__drive-card strong,
.main-dashboard__travel-kpis strong {
  color: #111827;
  font-size: 1rem;
  overflow-wrap: anywhere;
}

.main-dashboard__metric.is-positive strong,
.is-positive {
  color: #047857;
}

.main-dashboard__metric.is-negative strong,
.is-negative {
  color: #b91c1c;
}

.main-dashboard__travel-kpis {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.main-dashboard__split-row,
.main-dashboard__list-row {
  align-items: center;
  border-top: 1px solid #edf0f4;
  display: flex;
  gap: 10px;
  justify-content: space-between;
  min-width: 0;
  padding-top: 8px;
}

.main-dashboard__list {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.main-dashboard__list-row span {
  color: #374151;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-dashboard__list-row strong,
.main-dashboard__split-row strong {
  color: #111827;
  flex: 0 0 auto;
  font-size: 0.82rem;
}

.main-dashboard__drive-card small {
  color: #6b7280;
  font-size: 0.72rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-dashboard__features {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.main-dashboard__feature {
  background: #ffffff;
  border: 1px solid #d9dde5;
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 14px;
}

.main-dashboard__feature strong {
  color: #111827;
  font-size: 1rem;
}

.main-dashboard__feature p {
  min-height: 44px;
}

@media (max-width: 1080px) {
  .main-dashboard__summary-grid,
  .main-dashboard__features {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .main-dashboard__section--wide {
    grid-row: span 1;
  }
}

@media (max-width: 720px) {
  .main-dashboard__header {
    align-items: stretch;
    display: grid;
  }

  .main-dashboard__summary-grid,
  .main-dashboard__features,
  .main-dashboard__metric-grid,
  .main-dashboard__drive-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

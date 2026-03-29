<script setup>
import { onMounted, reactive } from 'vue'
import { fetchAdminDashboard, unlockBlockedIp, updateAdminUserActive } from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
})

const state = reactive({
  loading: true,
  mutatingUserId: null,
  unlockingIp: '',
  errorMessage: '',
  summary: null,
  recentLoginLogs: [],
  blockedIps: [],
  users: [],
  recentInvites: [],
})

const summaryCards = [
  { key: 'totalUsers', label: '전체 사용자' },
  { key: 'activeUsers', label: '활성 사용자' },
  { key: 'adminUsers', label: '관리자 계정' },
  { key: 'blockedIpCount', label: '차단 IP' },
  { key: 'pendingInvites', label: '미사용 초대' },
  { key: 'recentFailureCount', label: '24시간 실패' },
]

const loginStatusLabel = {
  SUCCESS: '성공',
  BLOCKED: '차단',
  BAD_CREDENTIALS: '비밀번호 실패',
  BAD_SECONDARY_PIN: '2차 비밀번호 실패',
}

const inviteStatusLabel = {
  PENDING: '사용 가능',
  USED: '사용 완료',
  EXPIRED: '만료',
}

function formatDateTime(value) {
  if (!value) {
    return '-'
  }

  const normalized = new Date(value)
  if (Number.isNaN(normalized.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(normalized)
}

async function loadDashboard() {
  state.loading = true
  state.errorMessage = ''

  try {
    const response = await fetchAdminDashboard()
    state.summary = response.summary
    state.recentLoginLogs = response.recentLoginLogs ?? []
    state.blockedIps = response.blockedIps ?? []
    state.users = response.users ?? []
    state.recentInvites = response.recentInvites ?? []
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.loading = false
  }
}

async function toggleUserActive(user) {
  state.mutatingUserId = user.id
  state.errorMessage = ''

  try {
    const updatedUser = await updateAdminUserActive(user.id, !user.active)
    state.users = state.users.map((item) => (item.id === updatedUser.id ? updatedUser : item))
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.mutatingUserId = null
  }
}

async function handleUnlockIp(ip) {
  state.unlockingIp = ip
  state.errorMessage = ''

  try {
    await unlockBlockedIp(ip)
    state.blockedIps = state.blockedIps.filter((item) => item.clientIp !== ip)
    if (state.summary) {
      state.summary = {
        ...state.summary,
        blockedIpCount: Math.max(0, (state.summary.blockedIpCount ?? 0) - 1),
      }
    }
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.unlockingIp = ''
  }
}

onMounted(loadDashboard)
</script>

<template>
  <section class="workspace-stack admin-workspace">
    <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>관리자 페이지</h2>
          <p>{{ currentUser.displayName }} 계정으로 로그인 시도, 차단 IP, 초대 현황, 사용자 상태를 확인할 수 있습니다.</p>
        </div>
        <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadDashboard">
          {{ state.loading ? '불러오는 중...' : '새로고침' }}
        </button>
      </div>
    </section>

    <section class="summary-grid">
      <article v-for="card in summaryCards" :key="card.key" class="summary-card">
        <span>{{ card.label }}</span>
        <strong>{{ state.summary?.[card.key] ?? 0 }}</strong>
      </article>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>현재 차단된 IP</h2>
          <p>5회 이상 실패로 24시간 차단된 IP를 확인하고 즉시 해제할 수 있습니다.</p>
        </div>
      </div>
      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>IP</th>
              <th>실패 횟수</th>
              <th>차단 해제 예정</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!state.blockedIps.length">
              <td colspan="4" class="sheet-table__empty">현재 차단된 IP가 없습니다.</td>
            </tr>
            <tr v-for="blockedIp in state.blockedIps" :key="blockedIp.clientIp">
              <td>{{ blockedIp.clientIp }}</td>
              <td>{{ blockedIp.failureCount }}회</td>
              <td>{{ formatDateTime(blockedIp.lockedUntil) }}</td>
              <td>
                <button
                  class="button button--ghost"
                  type="button"
                  :disabled="state.unlockingIp === blockedIp.clientIp"
                  @click="handleUnlockIp(blockedIp.clientIp)"
                >
                  {{ state.unlockingIp === blockedIp.clientIp ? '해제 중...' : '차단 해제' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>최근 로그인 로그</h2>
          <p>어떤 IP와 어떤 계정으로 로그인 시도가 들어왔는지 최근 기록 순서대로 확인합니다.</p>
        </div>
      </div>
      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>시각</th>
              <th>상태</th>
              <th>로그인 ID</th>
              <th>표시 이름</th>
              <th>IP</th>
              <th>세부 내용</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!state.recentLoginLogs.length">
              <td colspan="6" class="sheet-table__empty">아직 기록된 로그인 로그가 없습니다.</td>
            </tr>
            <tr v-for="log in state.recentLoginLogs" :key="log.id">
              <td>{{ formatDateTime(log.attemptedAt) }}</td>
              <td>
                <span :class="['entry-type-pill', log.success ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                  {{ loginStatusLabel[log.status] ?? log.status }}
                </span>
              </td>
              <td>{{ log.loginId }}</td>
              <td>{{ log.displayName || '-' }}</td>
              <td>{{ log.clientIp }}</td>
              <td>{{ log.detail || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>사용자 상태</h2>
          <p>관리자 여부와 활성 상태를 확인하고, 필요하면 계정을 비활성화하거나 다시 활성화할 수 있습니다.</p>
        </div>
      </div>
      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>로그인 ID</th>
              <th>이름</th>
              <th>권한</th>
              <th>상태</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!state.users.length">
              <td colspan="5" class="sheet-table__empty">사용자 정보가 없습니다.</td>
            </tr>
            <tr v-for="user in state.users" :key="user.id">
              <td>{{ user.loginId }}</td>
              <td>{{ user.displayName }}</td>
              <td>
                <span :class="['entry-type-pill', user.admin ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                  {{ user.admin ? '관리자' : '일반 사용자' }}
                </span>
              </td>
              <td>{{ user.active ? '활성' : '비활성' }}</td>
              <td>
                <button
                  class="button button--ghost"
                  type="button"
                  :disabled="state.mutatingUserId === user.id"
                  @click="toggleUserActive(user)"
                >
                  {{ state.mutatingUserId === user.id ? '저장 중...' : user.active ? '비활성화' : '활성화' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>최근 초대 링크</h2>
          <p>최근에 발급된 초대 링크가 사용됐는지, 아직 살아 있는지 확인할 수 있습니다.</p>
        </div>
      </div>
      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>생성 시각</th>
              <th>생성자</th>
              <th>만료 시각</th>
              <th>상태</th>
              <th>사용자</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!state.recentInvites.length">
              <td colspan="5" class="sheet-table__empty">초대 링크 기록이 없습니다.</td>
            </tr>
            <tr v-for="invite in state.recentInvites" :key="invite.id">
              <td>{{ formatDateTime(invite.createdAt) }}</td>
              <td>{{ invite.createdByDisplayName }} ({{ invite.createdByLoginId }})</td>
              <td>{{ formatDateTime(invite.expiresAt) }}</td>
              <td>{{ inviteStatusLabel[invite.status] ?? invite.status }}</td>
              <td>
                <template v-if="invite.usedByLoginId">
                  {{ invite.usedByDisplayName }} ({{ invite.usedByLoginId }})
                </template>
                <template v-else>
                  -
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

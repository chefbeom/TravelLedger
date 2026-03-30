<script setup>
import { computed, onMounted, reactive } from 'vue'
import {
  fetchAdminDashboard,
  fetchAdminLoginAuditLogs,
  fetchAdminSupportInquiries,
  replyAdminSupportInquiry,
  unlockBlockedIp,
  updateAdminUserActive,
} from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
})

const state = reactive({
  loading: true,
  loadingLoginLogs: false,
  mutatingUserId: null,
  unlockingIp: '',
  savingReply: false,
  errorMessage: '',
  summary: null,
  recentLoginLogs: [],
  loginLogPage: { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 },
  blockedIps: [],
  users: [],
  recentInvites: [],
  supportInquiries: [],
  selectedSupportInquiryId: null,
  supportReplyContent: '',
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
  FAILED: '인증 실패',
}

const inviteStatusLabel = {
  PENDING: '사용 가능',
  USED: '사용 완료',
  EXPIRED: '만료',
}

const inquiryStatusLabel = {
  PENDING: '답변 대기',
  ANSWERED: '답변 완료',
}

const selectedSupportInquiry = computed(() => (
  state.supportInquiries.find((inquiry) => inquiry.id === state.selectedSupportInquiryId) ?? null
))

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

function selectSupportInquiry(inquiry) {
  state.selectedSupportInquiryId = inquiry?.id ?? null
  state.supportReplyContent = inquiry?.replyContent ?? ''
}

async function loadDashboard() {
  state.loading = true
  state.errorMessage = ''

  try {
    const [dashboard, supportInquiries, loginLogPage] = await Promise.all([
      fetchAdminDashboard(),
      fetchAdminSupportInquiries(),
      fetchAdminLoginAuditLogs(0),
    ])

    state.summary = dashboard.summary
    state.loginLogPage = loginLogPage
    state.recentLoginLogs = loginLogPage.content ?? []
    state.blockedIps = dashboard.blockedIps ?? []
    state.users = dashboard.users ?? []
    state.recentInvites = dashboard.recentInvites ?? []
    state.supportInquiries = supportInquiries ?? []

    const matchedSelectedInquiry = state.supportInquiries.find((item) => item.id === state.selectedSupportInquiryId)
    if (matchedSelectedInquiry) {
      selectSupportInquiry(matchedSelectedInquiry)
    } else if (state.supportInquiries.length) {
      selectSupportInquiry(state.supportInquiries[0])
    } else {
      selectSupportInquiry(null)
    }
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.loading = false
  }
}

async function loadLoginAuditLogs(page = 0) {
  state.loadingLoginLogs = true
  state.errorMessage = ''

  try {
    state.loginLogPage = await fetchAdminLoginAuditLogs(page)
    state.recentLoginLogs = state.loginLogPage.content ?? []
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.loadingLoginLogs = false
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

async function handleReplySupportInquiry() {
  if (!selectedSupportInquiry.value) {
    return
  }

  state.savingReply = true
  state.errorMessage = ''

  try {
    const updatedInquiry = await replyAdminSupportInquiry(
      selectedSupportInquiry.value.id,
      state.supportReplyContent,
    )
    state.supportInquiries = state.supportInquiries.map((item) => (
      item.id === updatedInquiry.id ? updatedInquiry : item
    ))
    selectSupportInquiry(updatedInquiry)
  } catch (error) {
    state.errorMessage = error.message
  } finally {
    state.savingReply = false
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
          <p>{{ currentUser.displayName }} 계정으로 로그인한 상태에서 사용자 상태, 로그인 기록, 문의 메일함을 관리합니다.</p>
        </div>
        <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadDashboard">
          {{ state.loading ? '불러오는 중..' : '새로고침' }}
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
          <h2>문의 메일함</h2>
          <p>사용자가 보낸 요청사항과 건의사항을 확인하고, 선택한 메일에 바로 답변할 수 있습니다.</p>
        </div>
      </div>
      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>보낸 시각</th>
              <th>상태</th>
              <th>보낸 사람</th>
              <th>제목</th>
              <th>첨부</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!state.supportInquiries.length">
              <td colspan="6" class="sheet-table__empty">아직 문의 메일이 없습니다.</td>
            </tr>
            <tr
              v-for="inquiry in state.supportInquiries"
              :key="inquiry.id"
              :class="{ 'support-inquiry-row--selected': inquiry.id === state.selectedSupportInquiryId }"
            >
              <td>{{ formatDateTime(inquiry.createdAt) }}</td>
              <td>
                <span :class="['entry-type-pill', inquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                  {{ inquiryStatusLabel[inquiry.status] ?? inquiry.status }}
                </span>
              </td>
              <td>{{ inquiry.senderDisplayName }} ({{ inquiry.senderLoginId }})</td>
              <td>{{ inquiry.title }}</td>
              <td>{{ inquiry.attachmentFileName || '-' }}</td>
              <td>
                <button class="button button--ghost" type="button" @click="selectSupportInquiry(inquiry)">
                  {{ inquiry.id === state.selectedSupportInquiryId ? '선택됨' : '열기' }}
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
          <h2>문의 상세 / 답변</h2>
          <p>선택한 메일의 내용을 확인하고, 답변을 저장하면 사용자 프로필에 바로 표시됩니다.</p>
        </div>
      </div>

      <div v-if="selectedSupportInquiry" class="support-detail-grid">
        <div class="support-inquiry-card">
          <div class="support-inquiry-meta">
            <strong>{{ selectedSupportInquiry.title }}</strong>
            <span :class="['entry-type-pill', selectedSupportInquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
              {{ inquiryStatusLabel[selectedSupportInquiry.status] ?? selectedSupportInquiry.status }}
            </span>
          </div>
          <small>{{ selectedSupportInquiry.senderDisplayName }} ({{ selectedSupportInquiry.senderLoginId }}) · {{ formatDateTime(selectedSupportInquiry.createdAt) }}</small>
          <p class="support-inquiry-content">{{ selectedSupportInquiry.content }}</p>

          <div v-if="selectedSupportInquiry.attachmentUrl" class="support-inquiry-attachment">
            <a class="button button--ghost" :href="selectedSupportInquiry.attachmentUrl" target="_blank" rel="noreferrer">
              첨부 이미지 열기
            </a>
            <img
              v-if="selectedSupportInquiry.attachmentContentType?.startsWith('image/')"
              :src="selectedSupportInquiry.attachmentUrl"
              :alt="selectedSupportInquiry.attachmentFileName || selectedSupportInquiry.title"
              class="support-inquiry-preview"
            />
          </div>
        </div>

        <div class="support-inquiry-card">
          <div class="support-inquiry-reply__header">
            <strong>답변하기</strong>
            <small>
              {{ selectedSupportInquiry.replyContent ? '기존 답변을 수정할 수 있습니다.' : '아직 답변되지 않은 메일입니다.' }}
            </small>
          </div>
          <textarea
            v-model="state.supportReplyContent"
            rows="8"
            placeholder="사용자에게 전달할 답변을 입력해 주세요."
            :disabled="state.savingReply"
          />
          <div class="support-inquiry-actions">
            <button class="button button--primary" type="button" :disabled="state.savingReply" @click="handleReplySupportInquiry">
              {{ state.savingReply ? '저장 중..' : selectedSupportInquiry.replyContent ? '답변 수정 저장' : '답변 저장' }}
            </button>
          </div>
        </div>
      </div>
      <p v-else class="panel__empty">왼쪽 메일함 목록에서 확인할 문의를 선택해 주세요.</p>
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
                  {{ state.unlockingIp === blockedIp.clientIp ? '해제 중..' : '차단 해제' }}
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
          <h2>최근 로그인 기록</h2>
          <p>최근 10개씩 보여주며, 이전 기록은 페이지로 넘겨 확인할 수 있습니다.</p>
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
            </tr>
          </thead>
          <tbody>
            <tr v-if="!state.recentLoginLogs.length">
              <td colspan="5" class="sheet-table__empty">아직 기록된 로그인 로그가 없습니다.</td>
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
            </tr>
          </tbody>
        </table>
      </div>
      <div class="panel__actions">
        <button
          class="button button--ghost"
          type="button"
          :disabled="state.loadingLoginLogs || state.loginLogPage.page <= 0"
          @click="loadLoginAuditLogs(state.loginLogPage.page - 1)"
        >
          이전
        </button>
        <span>{{ state.loginLogPage.page + 1 }} / {{ Math.max(state.loginLogPage.totalPages, 1) }}</span>
        <button
          class="button button--ghost"
          type="button"
          :disabled="state.loadingLoginLogs || state.loginLogPage.page + 1 >= Math.max(state.loginLogPage.totalPages, 1)"
          @click="loadLoginAuditLogs(state.loginLogPage.page + 1)"
        >
          다음
        </button>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>사용자 상태</h2>
          <p>관리자 여부와 활성 상태를 확인하고, 일반 사용자만 활성/비활성 처리할 수 있습니다.</p>
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
                <template v-if="user.admin">-</template>
                <button
                  v-else
                  class="button button--ghost"
                  type="button"
                  :disabled="state.mutatingUserId === user.id"
                  @click="toggleUserActive(user)"
                >
                  {{ state.mutatingUserId === user.id ? '처리 중..' : user.active ? '비활성화' : '활성화' }}
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
          <p>최근에 발급한 초대 링크가 사용됐는지, 아직 남아 있는지 확인할 수 있습니다.</p>
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
                <template v-else>-</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

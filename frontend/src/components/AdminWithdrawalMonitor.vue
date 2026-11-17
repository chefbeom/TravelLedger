<script setup>
import { onMounted, reactive } from 'vue'
import { fetchAdminWithdrawalMonitor, fetchAdminWithdrawalTasks, retryAdminWithdrawalTask } from '../lib/api'

const state = reactive({ page: 0, hasNext: false, content: [], loading: false, error: '', message: '',
  requestId: '', taskPage: 0, taskHasNext: false, taskContent: [], taskLoading: false,
  retryTask: null, retryPin: '', retryBusy: false })
const kindLabels = {
  KAKAO_UNLINK: '카카오 연결 해제', FAMILY_MEDIA: '가족 앨범 사진', TRAVEL_MEDIA: '여행 사진',
  TRAVEL_FILE_INVENTORY: '여행 파일 목록', DRIVE_PROFILE_INVENTORY: '드라이브 프로필 목록',
  DRIVE_FILE_INVENTORY: '드라이브 파일 목록', DRIVE_UPLOAD_GRANT_INVENTORY: '드라이브 업로드 목록',
  FAMILY_UPLOAD_GRANT_INVENTORY: '가족 앨범 업로드 목록', TRAVEL_UPLOAD_GRANT_INVENTORY: '여행 업로드 목록',
  RECORD_MAP_MEDIA: '기록 지도 사진', DRIVE_OBJECT: '드라이브 객체', LEDGER_IMAGE: '가계부 분석 이미지',
  SUPPORT_ATTACHMENT: '문의 첨부파일', EMBEDDING_DELETE: '검색 벡터',
}
const stateLabels = { PENDING: '대기', PROCESSING: '처리 중', SUCCEEDED: '작업 성공', FAILED: '확인 필요' }
const formatDate = value => {
  const date = new Date(value)
  return value && !Number.isNaN(date.getTime()) ? date.toLocaleString('ko-KR') : '-'
}

async function load(page = state.page) {
  if (state.loading || page < 0) return
  state.loading = true
  state.error = ''
  try {
    const result = await fetchAdminWithdrawalMonitor(page)
    state.page = result.page
    state.hasNext = result.hasNext === true
    state.content = Array.isArray(result.content) ? result.content : []
  } catch (error) {
    state.error = error?.message || '탈퇴 작업을 불러오지 못했습니다.'
  } finally {
    state.loading = false
  }
}
onMounted(() => load())

async function loadTasks(requestId, page = 0) {
  if (state.taskLoading || state.retryBusy || page < 0) return
  state.taskLoading = true
  state.requestId = requestId
  state.retryTask = null
  state.retryPin = ''
  state.error = ''
  state.message = ''
  try {
    const result = await fetchAdminWithdrawalTasks(requestId, page)
    state.taskPage = result.page
    state.taskHasNext = result.hasNext === true
    state.taskContent = Array.isArray(result.content) ? result.content : []
  } catch (error) {
    state.taskContent = []
    state.error = error?.message || '정리 작업 상세를 불러오지 못했습니다.'
  } finally { state.taskLoading = false }
}

function chooseRetry(task) {
  if (state.retryBusy || !task?.retryable) return
  state.retryTask = task
  state.retryPin = ''
  state.message = ''
  state.error = ''
}

async function retry() {
  if (state.retryBusy || !state.retryTask || !/^\d{8}$/.test(state.retryPin)) return
  const pin = state.retryPin
  const taskId = state.retryTask.taskId
  const requestId = state.requestId
  state.retryPin = ''
  state.retryBusy = true
  state.error = ''
  let accepted = false
  try {
    await retryAdminWithdrawalTask(requestId, taskId, pin)
    state.retryTask = null
    accepted = true
  } catch (error) {
    state.error = error?.message || '작업을 다시 요청하지 못했습니다.'
  } finally {
    state.retryBusy = false
  }
  if (accepted) {
    await load(state.page)
    const listError = state.error
    await loadTasks(requestId, state.taskPage)
    if (listError && !state.error) state.error = listError
    state.message = '작업을 다시 대기 상태로 변경했습니다. 실행기와 결과를 별도로 확인해 주세요.'
  }
}
</script>

<template>
  <section class="admin-access-control-panel">
    <div class="panel__header">
      <div><h3>탈퇴 정리 상태 · 격리 테스트</h3></div>
      <button class="button button--ghost" type="button" :disabled="state.loading" @click="load()">새로고침</button>
    </div>
    <p class="form-hint">요청별 작업 집계만 표시합니다. 목록화는 파일 삭제가 아니며, 작업이 모두 성공해도 백업·외부 서비스 정리를 포함한 탈퇴 완료를 뜻하지 않습니다.</p>
    <p v-if="state.error" class="feedback feedback--error" role="alert">{{ state.error }}</p>
    <p v-if="state.loading" role="status">탈퇴 작업을 불러오는 중입니다...</p>
    <p v-else-if="!state.content.length" class="panel__empty">이 페이지에 탈퇴 요청이 없습니다.</p>
    <div v-else class="sheet-table-wrap">
      <table class="sheet-table">
        <thead><tr><th>요청 식별자</th><th>접수 시각</th><th>상태</th><th>작업 종류별 현황</th><th>상세</th></tr></thead>
        <tbody>
          <tr v-for="item in state.content" :key="item.requestId">
            <td>{{ item.requestId }}</td>
            <td>{{ formatDate(item.requestedAt) }}</td>
            <td>정리 진행 중</td>
            <td>
              <span v-if="!item.tasks?.length">기록된 정리 작업 없음 · 완료 여부 미확인</span>
              <ul v-else>
                <li v-for="task in item.tasks" :key="`${task.kind}-${task.state}`">
                  {{ kindLabels[task.kind] || task.kind }} · {{ stateLabels[task.state] || task.state }} {{ task.count }}건
                </li>
              </ul>
            </td>
            <td><button class="button button--ghost" type="button" :disabled="state.taskLoading || state.retryBusy" @click="loadTasks(item.requestId)">작업 상세</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="panel__actions admin-access-pagination">
      <button class="button button--ghost" type="button" :disabled="state.loading || state.page <= 0" @click="load(state.page - 1)">이전</button>
      <span>{{ state.page + 1 }} 페이지</span>
      <button class="button button--ghost" type="button" :disabled="state.loading || !state.hasNext" @click="load(state.page + 1)">다음</button>
    </div>
    <section v-if="state.requestId" class="stack-form" aria-label="탈퇴 정리 작업 상세">
      <h4>작업 상세 · {{ state.requestId }}</h4>
      <p class="form-hint">재시도는 대상 삭제를 직접 실행하지 않고 검증된 일시 오류만 대기열에 다시 넣습니다. 연결 상태나 삭제 결과가 불명확한 작업은 별도 확인이 필요합니다.</p>
      <p v-if="state.message" class="feedback feedback--success" role="status">{{ state.message }}</p>
      <p v-if="state.taskLoading" role="status">작업 상세를 불러오는 중입니다...</p>
      <p v-else-if="!state.taskContent.length">이 페이지에 정리 작업이 없습니다.</p>
      <div v-else class="sheet-table-wrap">
        <table class="sheet-table">
          <thead><tr><th>작업 종류</th><th>상태</th><th>시도</th><th>확인 코드</th><th>작업</th></tr></thead>
          <tbody><tr v-for="task in state.taskContent" :key="task.taskId">
            <td>{{ kindLabels[task.kind] || task.kind }}</td>
            <td>{{ stateLabels[task.state] || task.state }}</td>
            <td>{{ task.attempts }}회</td><td>{{ task.errorCode || '-' }}</td>
            <td><button v-if="task.retryable" class="button button--ghost" type="button" :disabled="state.retryBusy" @click="chooseRetry(task)">재시도</button><span v-else>-</span></td>
          </tr></tbody>
        </table>
      </div>
      <div class="panel__actions admin-access-pagination">
        <button class="button button--ghost" type="button" :disabled="state.taskLoading || state.retryBusy || state.taskPage <= 0" @click="loadTasks(state.requestId, state.taskPage - 1)">이전</button>
        <span>{{ state.taskPage + 1 }} 페이지</span>
        <button class="button button--ghost" type="button" :disabled="state.taskLoading || state.retryBusy || !state.taskHasNext" @click="loadTasks(state.requestId, state.taskPage + 1)">다음</button>
      </div>
      <form v-if="state.retryTask" class="stack-form" @submit.prevent="retry">
        <p>{{ kindLabels[state.retryTask.kind] || state.retryTask.kind }} 작업을 다시 대기시킵니다.</p>
        <label class="field"><span class="field__label">관리자 2차 비밀번호</span>
          <input v-model.trim="state.retryPin" type="password" inputmode="numeric" autocomplete="one-time-code" maxlength="8" :disabled="state.retryBusy" />
        </label>
        <div class="panel__actions">
          <button class="button button--primary" type="submit" :disabled="state.retryBusy || !/^\d{8}$/.test(state.retryPin)">재시도 확인</button>
          <button class="button button--ghost" type="button" :disabled="state.retryBusy" @click="state.retryTask = null; state.retryPin = ''">취소</button>
        </div>
      </form>
    </section>
  </section>
</template>

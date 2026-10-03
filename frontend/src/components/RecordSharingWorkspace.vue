<script setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref, watch } from 'vue'
import { fetchRecordShares, fetchRecordShareGroups, respondToRecordShare, fetchRecordSharedTravel,
  fetchCategories, fetchPaymentMethods, searchTravelShareRecipients, saveTravelShareGroup, deleteTravelShareGroup } from '../lib/api'
import { buildSharedLedgerDraft, buildSharedLedgerPayload } from '../lib/recordSharing'
import { formatCurrency, formatDateTime } from '../lib/uiFormat'

const TravelSharedExhibitWorkspace = defineAsyncComponent(() => import('./TravelSharedExhibitWorkspace.vue'))
const props = defineProps({ kind: { type: String, default: 'LEDGER' } })
const emit = defineEmits(['imported'])
const sent = ref(false)
const status = ref(props.kind === 'LEDGER' ? 'PENDING' : '')
const page = ref(0)
const result = ref({ items: [], totalElements: 0, totalPages: 0 })
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const selected = ref(null)
const travelDetail = ref(null)
const categories = ref([])
const payments = ref([])
const draft = reactive({})
const groups = ref([])
const groupForm = reactive({ id: null, name: '', query: '', recipients: [], editing: false })
const searchResults = ref([])
const searchBusy = ref(false)
const searchCompleted = ref(false)
const groupError = ref('')
const groupMessage = ref('')
let searchGeneration = 0
let generation = 0
const statusLabels = { PENDING: '승인 대기', ACCEPTED: '수락됨', REJECTED: '거절됨', CANCELED: '공유 취소' }
const availableGroups = computed(() => categories.value.filter((item) => item.active !== false && item.entryType === draft.entryType))
const details = computed(() => (availableGroups.value.find((item) => String(item.id) === String(draft.categoryGroupId))?.details || []).filter((item) => item.active !== false))
const ownGroups = computed(() => groups.value.filter((group) => group.members.some((member) => member.self && String(member.userId) === String(group.ownerId))))
function ownsGroup(group) { return ownGroups.value.some((item) => String(item.id) === String(group.id)) }
function isRecipientSelected(member) { return groupForm.recipients.some((item) => item.loginId.toLowerCase() === member.loginId.toLowerCase()) }
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const data = await fetchRecordShares({ kind: props.kind, sent: sent.value, status: status.value, page: page.value, size: 10 })
    if (current === generation) result.value = data
  } catch (ex) { if (current === generation) error.value = ex.message }
  finally { if (current === generation) loading.value = false }
}
function changed() {
  window.dispatchEvent(new Event('record-shares-changed'))
}
function switchList(value) {
  sent.value = value
  page.value = 0
  selected.value = null
  travelDetail.value = null
  load()
}
async function act(item, action, payload) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await respondToRecordShare(item.id, action, payload)
    changed()
    if (action === 'accept-ledger') { selected.value = null; emit('imported'); message.value = '내 가계부에 등록했습니다. 원본과 독립적으로 수정할 수 있습니다.' }
    else if (action === 'accept-travel') { await openTravel(item); message.value = '여행을 수락했습니다. 읽기 전용으로 감상할 수 있습니다.' }
    else { travelDetail.value = null; message.value = action === 'reject' ? '공유 요청을 거절했습니다.' : '공유를 취소했습니다.' }
    await load()
  } catch (ex) { error.value = ex.message }
  finally { busy.value = false }
}
async function openLedger(item) {
  busy.value = true
  error.value = ''
  try {
    const [groupList, paymentList] = await Promise.all([fetchCategories(), fetchPaymentMethods()])
    categories.value = groupList
    payments.value = paymentList.filter((payment) => payment.active !== false)
    Object.assign(draft, buildSharedLedgerDraft(item.ledger, categories.value, payments.value))
    selected.value = item
  } catch (ex) { error.value = ex.message }
  finally { busy.value = false }
}
async function openTravel(item) {
  travelDetail.value = null
  try { travelDetail.value = await fetchRecordSharedTravel(item.id) }
  catch (ex) { error.value = ex.message }
}
async function submitLedger() {
  if (!draft.categoryGroupId || draft.entryType === 'EXPENSE' && !draft.paymentMethodId) {
    error.value = '본인 가계부의 분류와 결제수단을 선택해 주세요.'
    return
  }
  await act(selected.value, 'accept-ledger', buildSharedLedgerPayload(draft))
}
async function searchMembers() {
  const query = groupForm.query.trim()
  if (busy.value || searchBusy.value || query.length < 2) return
  const current = ++searchGeneration
  searchBusy.value = true
  groupError.value = ''
  searchCompleted.value = false
  try {
    const members = await searchTravelShareRecipients(query)
    if (current === searchGeneration) { searchResults.value = members.filter((member) => !member.self); searchCompleted.value = true }
  } catch (ex) { if (current === searchGeneration) groupError.value = ex.message }
  finally { if (current === searchGeneration) searchBusy.value = false }
}
function addRecipient(member) {
  if (!busy.value && !member.self && !isRecipientSelected(member)) groupForm.recipients.push(member)
}
function resetGroupForm() {
  ++searchGeneration
  Object.assign(groupForm, { id: null, name: '', query: '', recipients: [], editing: false })
  searchResults.value = []
  searchBusy.value = false
  searchCompleted.value = false
  groupError.value = ''
}
function editGroup(group) {
  if (busy.value || !ownsGroup(group)) return
  resetGroupForm()
  groupMessage.value = ''
  groupForm.id = group.id
  groupForm.name = group.name
  groupForm.recipients = group.members.filter((member) => String(member.userId) !== String(group.ownerId))
  groupForm.editing = true
}
async function saveGroup() {
  if (busy.value || searchBusy.value) return
  const name = groupForm.name.trim()
  if (!name || !groupForm.recipients.length) { groupError.value = '그룹 이름을 입력하고 멤버를 한 명 이상 추가해 주세요.'; return }
  if (!groupForm.editing && ownGroups.value.some((group) => group.name.toLowerCase() === name.toLowerCase())) {
    groupError.value = '이미 같은 이름의 그룹이 있습니다. 저장된 그룹의 ‘멤버 수정’을 이용해 주세요.'
    return
  }
  busy.value = true
  groupError.value = ''
  groupMessage.value = ''
  try {
    await saveTravelShareGroup({ name, recipientLoginIds: groupForm.recipients.map((member) => member.loginId) })
    groups.value = await fetchRecordShareGroups()
    resetGroupForm()
    groupMessage.value = '공유 그룹을 저장했습니다. 기록 공유 시 받는 사람을 따로 선택해 주세요.'
    changed()
    await load()
  } catch (ex) { groupError.value = ex.message }
  finally { busy.value = false }
}
async function removeGroup(group) {
  if (busy.value || !ownsGroup(group)) return
  if (!window.confirm(`${group.name} 그룹을 삭제할까요? 대기 요청과 공유 여행 접근이 종료됩니다. 이미 가져온 가계부 거래는 유지됩니다.`)) return
  busy.value = true
  groupError.value = ''
  groupMessage.value = ''
  try {
    await deleteTravelShareGroup(group.id)
    groups.value = await fetchRecordShareGroups()
    if (String(groupForm.id) === String(group.id)) resetGroupForm()
    groupMessage.value = '공유 그룹을 삭제했습니다. 이미 가져온 가계부 거래는 유지됩니다.'
    changed(); await load()
  } catch (ex) { groupError.value = ex.message }
  finally { busy.value = false }
}
watch(() => groupForm.query, () => {
  ++searchGeneration
  searchResults.value = []
  searchBusy.value = false
  searchCompleted.value = false
})
watch(() => draft.entryType, () => {
  if (!availableGroups.value.some((item) => String(item.id) === String(draft.categoryGroupId))) {
    draft.categoryGroupId = availableGroups.value[0]?.id || ''
    draft.categoryDetailId = ''
  }
})
onMounted(async () => {
  await load()
  try { groups.value = await fetchRecordShareGroups() } catch (ex) { error.value = ex.message }
})
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header"><div><h2>{{ kind === 'LEDGER' ? '가계부 기록 공유' : '공유받은 여행' }}</h2><p>{{ kind === 'LEDGER' ? '승인한 기록만 내 가계부에 등록됩니다. 가져오기 전에 내용을 수정할 수 있습니다.' : '그룹에서 공유받은 여행을 수락하고 사진·지도·경로를 읽기 전용으로 감상하세요.' }}</p></div><button class="button button--ghost" :disabled="loading || busy" @click="load">새로고침</button></div>
      <div class="record-share-toolbar"><div class="scope-toggle"><button class="button" :class="{ 'button--primary': !sent }" :disabled="busy" @click="switchList(false)">받은 기록</button><button class="button" :class="{ 'button--primary': sent }" :disabled="busy" @click="switchList(true)">보낸 기록</button></div><label class="field"><span>처리 상태</span><select v-model="status" :disabled="busy" @change="page = 0; load()"><option value="">전체</option><option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option></select></label><span>{{ result.totalElements }}건</span></div>
      <p v-if="error" role="alert" class="record-share-error">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
      <p v-if="loading" aria-live="polite">공유 기록을 불러오는 중…</p>
      <div v-else class="record-share-list">
        <article v-for="item in result.items" :key="item.id" class="record-share-card">
          <div><span class="panel__eyebrow">{{ item.groupName }} · {{ sent ? `받는 사람 ${item.recipientName}` : `보낸 사람 ${item.senderName}` }}</span><h3>{{ item.title }}</h3><p v-if="item.ledger" :class="item.ledger.entryType === 'INCOME' ? 'is-income' : 'is-expense'">{{ item.ledger.entryType === 'INCOME' ? '수입' : '지출' }} {{ formatCurrency(item.ledger.amount) }} · {{ item.ledger.entryDate }} · {{ item.ledger.paymentMethodName }}</p><p v-if="item.ledger">{{ item.ledger.categoryGroupName }} / {{ item.ledger.categoryDetailName || '미분류' }}<span v-if="item.ledger.memo"> · {{ item.ledger.memo }}</span></p><section v-if="item.shareMemo" class="record-share-note" aria-label="공유 메모"><strong>공유 메모</strong><p>{{ item.shareMemo }}</p></section><div class="record-share-card__status"><span class="record-share-state" :data-status="item.status">{{ statusLabels[item.status] }}</span><small>요청 {{ formatDateTime(item.createdAt) }}</small></div><p v-if="item.importedLedgerEntryId">내 가계부에 등록된 독립 거래 #{{ item.importedLedgerEntryId }}</p></div>
          <div class="record-share-card__actions">
            <template v-if="!sent && item.status === 'PENDING'"><button class="button button--primary" :disabled="busy" @click="kind === 'LEDGER' ? openLedger(item) : act(item, 'accept-travel')">{{ kind === 'LEDGER' ? '확인 후 내 가계부에 기록' : '여행 수락' }}</button><button class="button button--ghost" :disabled="busy" @click="act(item, 'reject')">거절</button></template>
            <button v-if="!sent && kind === 'TRAVEL' && item.status === 'ACCEPTED'" class="button button--primary" :disabled="busy" @click="openTravel(item)">여행 열기</button>
            <button v-if="sent && (item.status === 'PENDING' || kind === 'TRAVEL' && item.status === 'ACCEPTED')" class="button button--ghost" :disabled="busy" @click="act(item, 'cancel')">공유 취소</button>
          </div>
        </article>
        <p v-if="!result.items.length" class="panel__empty">해당 조건의 공유 기록이 없습니다.</p>
      </div>
      <div v-if="result.totalPages > 1" class="panel__actions"><button class="button" :disabled="page === 0 || loading || busy" @click="page--; load()">이전</button><span>{{ page + 1 }} / {{ result.totalPages }}</span><button class="button" :disabled="page + 1 >= result.totalPages || loading || busy" @click="page++; load()">다음</button></div>
    </section>

    <details class="panel record-share-groups" open>
      <summary>공유 그룹 관리 · {{ groups.length }}개</summary>
      <p class="record-share-hint">그룹은 공유할 사람을 관리하는 목록입니다. 그룹에 추가해도 기록이 자동으로 공유되지는 않으며, 공유할 때 받는 사람을 선택합니다.</p>
      <p v-if="groupError" class="record-share-error" role="alert">{{ groupError }}</p>
      <p v-if="groupMessage" role="status">{{ groupMessage }}</p>
      <div class="record-share-group-layout">
        <section class="record-share-section" aria-labelledby="saved-share-groups-title">
          <div class="record-share-section__header"><h3 id="saved-share-groups-title">저장된 공유 그룹</h3><span class="record-share-state">{{ groups.length }}개</span></div>
          <p v-if="!groups.length" class="panel__empty">아직 그룹이 없습니다. 새 그룹 이름을 입력하고 검색 결과에서 멤버를 추가한 뒤 저장해 주세요.</p>
          <div class="record-share-list">
            <article v-for="group in groups" :key="group.id" class="record-share-group-card" :aria-label="`${group.name} 공유 그룹`">
              <div class="record-share-section__header"><h4>{{ group.name }}</h4><span class="record-share-state" :class="{ 'is-owner': ownsGroup(group) }">{{ ownsGroup(group) ? '내가 관리' : '참여 중' }}</span></div>
              <p class="record-share-hint">총 {{ group.members.length }}명 · {{ ownsGroup(group) ? '멤버 수정 가능' : '멤버 수정은 그룹 관리자만 가능' }}</p>
              <ul class="record-share-group-members">
                <li v-for="member in group.members" :key="member.userId"><div><strong>{{ member.displayName }}{{ member.self ? ' (나)' : '' }}</strong><small>{{ member.loginId }}</small></div><span class="record-share-state">{{ String(member.userId) === String(group.ownerId) ? '관리자' : '멤버' }}</span></li>
              </ul>
              <div v-if="ownsGroup(group)" class="record-share-card__actions"><button class="button button--ghost" :disabled="busy" @click="editGroup(group)">멤버 수정</button><button class="button button--danger" :disabled="busy" @click="removeGroup(group)">그룹 삭제</button></div>
            </article>
          </div>
        </section>
        <form class="record-share-section record-share-group-form" aria-labelledby="share-group-editor-title" @submit.prevent="saveGroup">
          <div class="record-share-section__header"><h3 id="share-group-editor-title">{{ groupForm.editing ? '그룹 멤버 수정' : '새 공유 그룹 만들기' }}</h3><span class="record-share-state">{{ groupForm.editing ? '수정 중 · 저장 필요' : '아직 저장되지 않음' }}</span></div>
          <label class="field"><span>{{ groupForm.editing ? '수정 중인 그룹 이름' : '새 그룹 이름' }}</span><input v-model="groupForm.name" required maxlength="80" :readonly="groupForm.editing" :disabled="busy" placeholder="예: 가족, 친구, 여행 모임" /></label>
          <section class="record-share-group-step" aria-labelledby="find-share-member-title">
            <h4 id="find-share-member-title">1. 멤버 찾기</h4>
            <div class="record-share-toolbar"><label class="field"><span>사용자 찾기</span><input v-model="groupForm.query" maxlength="80" :disabled="busy" placeholder="아이디 또는 이름 · 2글자 이상" @keydown.enter.prevent="searchMembers" /></label><button class="button button--ghost" type="button" :disabled="busy || searchBusy || groupForm.query.trim().length < 2" @click="searchMembers">{{ searchBusy ? '검색 중…' : '검색' }}</button></div>
            <div class="record-share-section__header"><h4>검색 결과</h4><small v-if="searchCompleted">{{ searchResults.length }}명</small></div>
            <p v-if="!searchCompleted && !searchBusy" class="record-share-hint">검색 결과의 ‘추가’ 버튼을 눌러 아래 멤버 목록에 넣어 주세요.</p>
            <p v-if="searchCompleted && !searchResults.length" class="panel__empty">일치하는 사용자가 없습니다. 아이디 또는 이름을 확인해 주세요.</p>
            <ul class="record-share-group-members">
              <li v-for="member in searchResults" :key="member.userId"><div><strong>{{ member.displayName }}</strong><small>{{ member.loginId }}</small></div><button type="button" class="button button--ghost" :aria-label="`${member.displayName} (${member.loginId}) 추가`" :disabled="busy || isRecipientSelected(member)" @click="addRecipient(member)">{{ isRecipientSelected(member) ? '추가됨' : '추가' }}</button></li>
            </ul>
          </section>
          <section class="record-share-group-step" aria-labelledby="selected-share-member-title">
            <div class="record-share-section__header"><h4 id="selected-share-member-title">2. 저장할 멤버 확인</h4><span class="record-share-state">{{ groupForm.recipients.length }}명 추가</span></div>
            <p class="record-share-hint">본인은 그룹 관리자로 자동 포함됩니다. 아래 멤버는 ‘그룹 저장’을 눌러야 반영됩니다.</p>
            <p v-if="!groupForm.recipients.length" class="panel__empty">추가한 멤버가 없습니다. 위 검색 결과에서 선택해 주세요.</p>
            <ul class="record-share-group-members">
              <li v-for="member in groupForm.recipients" :key="member.loginId"><div><strong>{{ member.displayName }}</strong><small>{{ member.loginId }}</small></div><button class="button button--ghost" type="button" :aria-label="`${member.displayName} (${member.loginId}) 제거`" :disabled="busy" @click="groupForm.recipients = groupForm.recipients.filter((item) => item.loginId !== member.loginId)">제거</button></li>
            </ul>
          </section>
          <div class="panel__actions"><button class="button button--primary" type="submit" :disabled="busy || searchBusy || !groupForm.name.trim() || !groupForm.recipients.length">{{ busy ? '저장 중…' : '그룹 저장' }}</button><button v-if="groupForm.editing || groupForm.name || groupForm.recipients.length" class="button button--ghost" type="button" :disabled="busy" @click="resetGroupForm">{{ groupForm.editing ? '수정 취소' : '입력 초기화' }}</button></div>
        </form>
      </div>
    </details>

    <TravelSharedExhibitWorkspace v-if="travelDetail" embedded :selected-exhibit="travelDetail" />
    <Teleport to="body"><div v-if="selected" class="record-share-modal" @click.self="!busy && (selected = null)" @keydown.esc="!busy && (selected = null)"><form class="panel record-share-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="import-shared-title" @submit.prevent="submitLedger">
      <div class="panel__header"><h2 id="import-shared-title">확인 후 내 가계부에 기록</h2><button class="button button--ghost" type="button" :disabled="busy" @click="selected = null">닫기</button></div><p>결제수단과 분류는 본인 가계부 기준입니다. 저장 후에도 자유롭게 수정할 수 있습니다.</p>
      <section v-if="selected.shareMemo" class="record-share-note" aria-label="공유 메모"><strong>{{ selected.senderName }}님이 남긴 공유 메모</strong><p>{{ selected.shareMemo }}</p><small>아래 거래 메모와 별개이며, 가계부에 자동으로 복사되지 않습니다.</small></section>
      <label class="field"><span>제목</span><input v-model="draft.title" required maxlength="120" /></label>
      <div class="record-share-form-grid"><label class="field"><span>날짜</span><input v-model="draft.entryDate" type="date" required /></label><label class="field"><span>시간 (선택)</span><input v-model="draft.entryTime" type="time" /></label><label class="field"><span>구분</span><select v-model="draft.entryType"><option value="EXPENSE">지출</option><option value="INCOME">수입</option></select></label><label class="field"><span>금액 (원)</span><input v-model="draft.amount" type="number" min="0.01" step="0.01" required /></label></div>
      <div v-if="draft.foreignCurrencyCode && draft.foreignCurrencyCode !== 'KRW'" class="record-share-form-grid"><label class="field"><span>외화 통화</span><input v-model="draft.foreignCurrencyCode" maxlength="3" required /></label><label class="field"><span>외화 금액</span><input v-model="draft.foreignAmount" type="number" step="0.0001" min="0.0001" required /></label><label class="field"><span>원화 환율</span><input v-model="draft.exchangeRateToKrw" type="number" min="0.000001" step="0.000001" required /></label></div>
      <div class="record-share-form-grid"><label v-if="draft.entryType === 'EXPENSE'" class="field"><span>내 결제수단</span><select v-model="draft.paymentMethodId" required><option value="" disabled>선택</option><option v-for="payment in payments" :key="payment.id" :value="payment.id">{{ payment.name }}</option></select></label><label class="field"><span>내 대분류</span><select v-model="draft.categoryGroupId" required @change="draft.categoryDetailId = ''"><option value="" disabled>선택</option><option v-for="group in availableGroups" :key="group.id" :value="group.id">{{ group.name }}</option></select></label><label class="field"><span>내 분류</span><select v-model="draft.categoryDetailId"><option value="">미분류</option><option v-for="detail in details" :key="detail.id" :value="detail.id">{{ detail.name }}</option></select></label></div>
      <label class="field"><span>메모</span><textarea v-model="draft.memo" maxlength="500" rows="3" /></label><p v-if="error" class="record-share-error" role="alert">{{ error }}</p><button class="button button--primary" type="submit" :disabled="busy">{{ busy ? '등록 중…' : '내 가계부에 등록' }}</button>
    </form></div></Teleport>
  </div>
</template>

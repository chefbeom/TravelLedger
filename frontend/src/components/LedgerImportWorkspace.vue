<script setup>
import { computed, ref } from 'vue'
import { commitLedgerExcelImport, previewLedgerAiExcelImport, previewLedgerExcelImport } from '../lib/api'

const emit = defineEmits(['imported'])

const importMode = ref('standard')
const selectedFile = ref(null)
const preview = ref(null)
const previewRows = ref([])
const isPreviewing = ref(false)
const isImporting = ref(false)
const feedback = ref('')
const errorMessage = ref('')

const selectedCount = computed(() => previewRows.value.filter((row) => row.selected).length)
const readyCount = computed(() => previewRows.value.filter((row) => row.ready).length)
const importModeLabel = computed(() => (importMode.value === 'ai' ? 'AI로 엑셀 데이터 가져오기' : '기존 Excel 가져오기'))

function normalizeAmountValue(value) {
  const normalized = String(value ?? '').replace(/,/g, '').trim()
  if (!normalized) {
    return ''
  }
  const amount = Number(normalized)
  return Number.isFinite(amount) ? String(amount) : normalized
}

function validatePreviewRow(row) {
  const issues = []
  const amount = Number(normalizeAmountValue(row.amount))

  if (!row.entryDate) {
    issues.push('거래일 필요')
  }
  if (!String(row.title || '').trim()) {
    issues.push('내용 필요')
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    issues.push('금액 필요')
  }
  if (!['EXPENSE', 'INCOME'].includes(row.entryType)) {
    issues.push('구분 필요')
  }

  return issues
}

function refreshPreviewRow(row) {
  row.amount = normalizeAmountValue(row.amount)
  row.title = String(row.title || '').trimStart()
  row.entryType = ['EXPENSE', 'INCOME'].includes(row.entryType) ? row.entryType : 'EXPENSE'
  row.issues = validatePreviewRow(row)
  row.ready = row.issues.length === 0
  if (!row.ready) {
    row.selected = false
  }
}

function toEditablePreviewRow(row) {
  const editableRow = {
    ...row,
    entryDate: row.entryDate || '',
    entryTime: row.entryTime || '',
    title: row.title || '',
    memo: row.memo || '',
    amount: normalizeAmountValue(row.amount),
    entryType: row.entryType || 'EXPENSE',
    paymentMethodName: row.paymentMethodName || '',
    categoryGroupName: row.categoryGroupName || '',
    categoryDetailName: row.categoryDetailName || '',
    selected: Boolean(row.ready),
  }
  refreshPreviewRow(editableRow)
  editableRow.selected = Boolean(editableRow.ready && row.ready !== false)
  return editableRow
}

function resetMessages() {
  feedback.value = ''
  errorMessage.value = ''
}

function resetPreview() {
  selectedFile.value = null
  preview.value = null
  previewRows.value = []
  resetMessages()
}

function handleFileChange(event) {
  selectedFile.value = event.target.files?.[0] ?? null
  preview.value = null
  previewRows.value = []
  resetMessages()
}

function changeImportMode(mode) {
  if (isPreviewing.value || isImporting.value) {
    return
  }
  importMode.value = mode
  preview.value = null
  previewRows.value = []
  resetMessages()
}

async function handlePreview() {
  if (!selectedFile.value) {
    errorMessage.value = '가져올 엑셀 파일을 먼저 선택해 주세요.'
    return
  }

  isPreviewing.value = true
  resetMessages()
  try {
    const previewFn = importMode.value === 'ai' ? previewLedgerAiExcelImport : previewLedgerExcelImport
    const response = await previewFn(selectedFile.value)
    preview.value = response
    previewRows.value = (response.rows ?? []).map(toEditablePreviewRow)
    feedback.value = `${importModeLabel.value}로 ${response.readyRowCount}개 거래를 가져올 준비가 됐습니다.`
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isPreviewing.value = false
  }
}

async function handleImport() {
  previewRows.value.forEach(refreshPreviewRow)
  const rows = previewRows.value
    .filter((row) => row.selected && row.ready)
    .map((row) => ({
      selected: true,
      sourceSheetName: row.sourceSheetName || null,
      entryDate: row.entryDate,
      entryTime: row.entryTime || null,
      title: String(row.title || '').trim(),
      memo: String(row.memo || '').trim() || null,
      amount: Number(normalizeAmountValue(row.amount)),
      entryType: row.entryType,
      paymentMethodName: String(row.paymentMethodName || '').trim() || null,
      categoryGroupName: String(row.categoryGroupName || '').trim() || null,
      categoryDetailName: String(row.categoryDetailName || '').trim() || null,
      sourceRowNumber: row.sourceRowNumber || null,
    }))

  if (!rows.length) {
    errorMessage.value = '가져올 거래가 없습니다. 준비 완료된 행을 선택해 주세요.'
    return
  }

  isImporting.value = true
  resetMessages()
  try {
    const result = await commitLedgerExcelImport(rows)
    feedback.value = `가계부에 ${result.importedCount}개 거래를 가져왔습니다.`
    previewRows.value = []
    preview.value = null
    selectedFile.value = null
    emit('imported', result)
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isImporting.value = false
  }
}
</script>

<template>
  <section class="panel ledger-import-panel">
    <div class="panel__header">
      <div>
        <h2>엑셀 가져오기</h2>
        <p>기존 정형 Excel 가져오기와 AI로 엑셀 데이터 가져오기를 선택할 수 있습니다. AI 방식도 최종 확인 전에는 DB에 삽입되지 않습니다.</p>
      </div>
      <span class="panel__badge">{{ preview ? `${selectedCount}개 선택` : importModeLabel }}</span>
    </div>

    <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <div class="ledger-import-summary">
      <button type="button" class="button" :class="importMode === 'standard' ? 'button--primary' : 'button--secondary'" :disabled="isPreviewing || isImporting" @click="changeImportMode('standard')">
        기존 Excel 가져오기
      </button>
      <button type="button" class="button" :class="importMode === 'ai' ? 'button--primary' : 'button--secondary'" :disabled="isPreviewing || isImporting" @click="changeImportMode('ai')">
        AI로 엑셀 데이터 가져오기
      </button>
      <span class="chip chip--neutral">{{ importMode === 'ai' ? 'AI가 형식, 위치, 분류를 추론합니다.' : '지원되는 정형 양식을 빠르게 분석합니다.' }}</span>
    </div>

    <div class="ledger-import-toolbar">
      <label class="field field--full">
        <span class="field__label">엑셀 파일</span>
        <input type="file" accept=".xlsx,.xls" @change="handleFileChange" />
      </label>
      <div class="ledger-import-toolbar__actions">
        <button class="button button--secondary" :disabled="isPreviewing || isImporting" @click="handlePreview">
          {{ isPreviewing ? '추출 중...' : (importMode === 'ai' ? 'AI 미리보기' : '미리보기') }}
        </button>
        <button class="button button--primary" :disabled="isPreviewing || isImporting || !previewRows.length" @click="handleImport">
          {{ isImporting ? '가져오는 중...' : '선택 행 가져오기' }}
        </button>
        <button class="button button--ghost" :disabled="isPreviewing || isImporting" @click="resetPreview">초기화</button>
      </div>
    </div>

    <div class="ledger-import-summary">
      <span class="chip chip--neutral">지원 형식 .xlsx / .xls</span>
      <span class="chip chip--neutral">{{ importModeLabel }}</span>
      <span v-if="preview" class="chip chip--neutral">시트 {{ preview.sheetName }}</span>
      <span v-if="preview" class="chip chip--neutral">헤더 행 {{ preview.headerRowNumber }}행</span>
      <span v-if="preview" class="chip chip--neutral">준비 완료 {{ readyCount }}건</span>
    </div>

    <div v-if="preview?.notes?.length" class="ledger-import-notes">
      <p v-for="note in preview.notes" :key="note">{{ note }}</p>
    </div>

    <div v-if="previewRows.length" class="sheet-table-wrap">
      <table class="sheet-table ledger-import-table ledger-import-table--editable">
        <thead>
          <tr>
            <th>선택</th>
            <th>원본 행</th>
            <th>시트</th>
            <th>거래일</th>
            <th>시각</th>
            <th>구분</th>
            <th>내용</th>
            <th>금액</th>
            <th>결제수단</th>
            <th>대분류</th>
            <th>소분류</th>
            <th>메모</th>
            <th>상태</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in previewRows" :key="`editable-${row.previewIndex}-${row.sourceRowNumber}`" :class="{ 'is-disabled': !row.ready }">
            <td>
              <input v-model="row.selected" type="checkbox" :disabled="!row.ready || isImporting" />
            </td>
            <td>{{ row.sourceRowNumber || '-' }}</td>
            <td>{{ row.sourceSheetName || '-' }}</td>
            <td>
              <input
                v-model="row.entryDate"
                class="sheet-table__input ledger-import-table__date"
                type="date"
                :disabled="isImporting"
                @change="refreshPreviewRow(row)"
              />
            </td>
            <td>
              <input
                v-model="row.entryTime"
                class="sheet-table__input ledger-import-table__time"
                type="time"
                :disabled="isImporting"
              />
            </td>
            <td>
              <select
                v-model="row.entryType"
                class="sheet-table__select-input ledger-import-table__type"
                :disabled="isImporting"
                @change="refreshPreviewRow(row)"
              >
                <option value="EXPENSE">지출</option>
                <option value="INCOME">수입</option>
              </select>
            </td>
            <td class="sheet-table__title">
              <input
                v-model="row.title"
                class="sheet-table__input ledger-import-table__title"
                type="text"
                :disabled="isImporting"
                @input="refreshPreviewRow(row)"
              />
            </td>
            <td>
              <input
                v-model="row.amount"
                class="sheet-table__input ledger-import-table__amount"
                inputmode="decimal"
                :disabled="isImporting"
                @change="refreshPreviewRow(row)"
              />
            </td>
            <td>
              <input
                v-model="row.paymentMethodName"
                class="sheet-table__input ledger-import-table__payment"
                type="text"
                :disabled="isImporting"
              />
            </td>
            <td>
              <input
                v-model="row.categoryGroupName"
                class="sheet-table__input ledger-import-table__category"
                type="text"
                :disabled="isImporting"
              />
            </td>
            <td>
              <input
                v-model="row.categoryDetailName"
                class="sheet-table__input ledger-import-table__category"
                type="text"
                :disabled="isImporting"
              />
            </td>
            <td>
              <input
                v-model="row.memo"
                class="sheet-table__input ledger-import-table__memo"
                type="text"
                :disabled="isImporting"
              />
            </td>
            <td>
              <span v-if="row.ready" class="chip chip--neutral">준비 완료</span>
              <span v-else class="chip chip--warning">{{ row.issues?.join(', ') || '확인 필요' }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="panel__empty">사용하던 가계부 엑셀 파일을 올리면 선택한 방식으로 거래일, 내용, 금액, 분류를 미리보기로 만들 수 있습니다.</p>
  </section>
</template>
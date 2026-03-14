<script setup>
import { computed, ref } from 'vue'
import { commitLedgerExcelImport, previewLedgerExcelImport } from '../lib/api'
import { formatCurrency, formatShortDate } from '../lib/format'

const emit = defineEmits(['imported'])

const selectedFile = ref(null)
const preview = ref(null)
const previewRows = ref([])
const isPreviewing = ref(false)
const isImporting = ref(false)
const feedback = ref('')
const errorMessage = ref('')

const selectedCount = computed(() => previewRows.value.filter((row) => row.selected).length)
const readyCount = computed(() => previewRows.value.filter((row) => row.ready).length)

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

async function handlePreview() {
  if (!selectedFile.value) {
    errorMessage.value = '가져올 엑셀 파일을 먼저 선택해 주세요.'
    return
  }

  isPreviewing.value = true
  resetMessages()
  try {
    const response = await previewLedgerExcelImport(selectedFile.value)
    preview.value = response
    previewRows.value = (response.rows ?? []).map((row) => ({
      ...row,
      selected: Boolean(row.ready),
    }))
    feedback.value = `엑셀에서 ${response.readyRowCount}개 거래를 가져올 준비가 됐습니다.`
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    isPreviewing.value = false
  }
}

async function handleImport() {
  const rows = previewRows.value
    .filter((row) => row.selected && row.ready)
    .map((row) => ({
      selected: true,
      sourceSheetName: row.sourceSheetName || null,
      entryDate: row.entryDate,
      entryTime: row.entryTime || null,
      title: row.title,
      memo: row.memo || null,
      amount: row.amount,
      entryType: row.entryType,
      paymentMethodName: row.paymentMethodName || null,
      categoryGroupName: row.categoryGroupName || null,
      categoryDetailName: row.categoryDetailName || null,
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
        <p>기존 가계부 엑셀 파일을 올리면 거래 내역만 추출해 현재 가계부 형식으로 가져옵니다.</p>
      </div>
      <span class="panel__badge">{{ preview ? `${selectedCount}개 선택` : '준비 중' }}</span>
    </div>

    <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <div class="ledger-import-toolbar">
      <label class="field field--full">
        <span class="field__label">엑셀 파일</span>
        <input type="file" accept=".xlsx,.xls" @change="handleFileChange" />
      </label>
      <div class="ledger-import-toolbar__actions">
        <button class="button button--secondary" :disabled="isPreviewing || isImporting" @click="handlePreview">
          {{ isPreviewing ? '추출 중...' : '미리보기' }}
        </button>
        <button class="button button--primary" :disabled="isPreviewing || isImporting || !previewRows.length" @click="handleImport">
          {{ isImporting ? '가져오는 중...' : '선택 행 가져오기' }}
        </button>
        <button class="button button--ghost" :disabled="isPreviewing || isImporting" @click="resetPreview">초기화</button>
      </div>
    </div>

    <div class="ledger-import-summary">
      <span class="chip chip--neutral">지원 형식 .xlsx / .xls</span>
      <span v-if="preview" class="chip chip--neutral">시트 {{ preview.sheetName }}</span>
      <span v-if="preview" class="chip chip--neutral">헤더 행 {{ preview.headerRowNumber }}행</span>
      <span v-if="preview" class="chip chip--neutral">준비 완료 {{ readyCount }}건</span>
    </div>

    <div v-if="preview?.notes?.length" class="ledger-import-notes">
      <p v-for="note in preview.notes" :key="note">{{ note }}</p>
    </div>

    <div v-if="previewRows.length" class="sheet-table-wrap">
      <table class="sheet-table ledger-import-table">
        <thead>
          <tr>
            <th>선택</th>
            <th>원본 행</th>
            <th>월 시트</th>
            <th>거래일</th>
            <th>내용</th>
            <th>금액</th>
            <th>결제수단</th>
            <th>대분류</th>
            <th>소분류</th>
            <th>상태</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in previewRows" :key="`${row.previewIndex}-${row.sourceRowNumber}`" :class="{ 'is-disabled': !row.ready }">
            <td>
              <input v-model="row.selected" type="checkbox" :disabled="!row.ready || isImporting" />
            </td>
            <td>{{ row.sourceRowNumber || '-' }}</td>
            <td>{{ row.sourceSheetName || '-' }}</td>
            <td>{{ row.entryDate ? formatShortDate(row.entryDate) : '-' }}</td>
            <td class="sheet-table__title">{{ row.title || '-' }}</td>
            <td>{{ row.amount ? formatCurrency(row.amount) : '-' }}</td>
            <td>{{ row.paymentMethodName || '-' }}</td>
            <td>{{ row.categoryGroupName || '-' }}</td>
            <td>{{ row.categoryDetailName || '-' }}</td>
            <td>
              <span v-if="row.ready" class="chip chip--neutral">준비 완료</span>
              <span v-else class="chip chip--warning">{{ row.issues?.join(', ') || '확인 필요' }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="panel__empty">실제 가계부 엑셀 파일을 올리면 거래일, 지출내용, 지출금액 열을 찾아 미리보기를 만들어 드립니다.</p>
  </section>
</template>

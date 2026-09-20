<script setup>
import { computed, reactive, ref, watch } from 'vue'

const props = defineProps({
  categories: {
    type: Array,
    default: () => [],
  },
  paymentMethods: {
    type: Array,
    default: () => [],
  },
  managementCategories: {
    type: Array,
    default: () => [],
  },
  managementPaymentMethods: {
    type: Array,
    default: () => [],
  },
  groupForm: {
    type: Object,
    required: true,
  },
  detailForm: {
    type: Object,
    required: true,
  },
  paymentForm: {
    type: Object,
    required: true,
  },
  isSubmitting: {
    type: Boolean,
    default: false,
  },
  activeSubmit: {
    type: String,
    default: '',
  },
})

const emit = defineEmits([
  'create-group',
  'create-detail',
  'create-payment',
  'deactivate-group',
  'deactivate-detail',
  'deactivate-payment',
  'activate-group',
  'activate-detail',
  'activate-payment',
  'reorder-groups',
  'reorder-details',
  'reorder-payments',
  'delete-group',
  'delete-detail',
  'delete-payment',
])

const isEditMode = ref(false)
const workingCategories = ref([])
const workingPaymentMethods = ref([])
const dragState = reactive({
  type: '',
  sourceId: '',
  sourceGroupId: '',
  overKey: '',
  position: '',
})

function cloneCategories(categories = []) {
  return categories.map((group) => ({
    ...group,
    details: Array.isArray(group.details)
      ? group.details.map((detail) => ({ ...detail }))
      : [],
  }))
}

function clonePaymentMethods(paymentMethods = []) {
  return paymentMethods.map((payment) => ({ ...payment }))
}

watch(() => props.managementCategories, (categories) => {
  workingCategories.value = cloneCategories(categories)
}, { immediate: true, deep: true })

watch(() => props.managementPaymentMethods, (paymentMethods) => {
  workingPaymentMethods.value = clonePaymentMethods(paymentMethods)
}, { immediate: true, deep: true })

const catalogCategories = computed(() => (
  isEditMode.value ? workingCategories.value : props.categories
))

const catalogPaymentMethods = computed(() => (
  isEditMode.value ? workingPaymentMethods.value : props.paymentMethods
))

const editModeLabel = computed(() => (isEditMode.value ? '수정 끝내기' : '분류 수정하기'))

function isActive(item) {
  return item?.active !== false
}

function entryTypeLabel(entryType) {
  return entryType === 'INCOME' ? '수입' : '지출'
}

function paymentKindLabel(kind) {
  const labels = {
    CARD: '카드',
    CASH: '현금',
    POINT: '포인트',
    TRANSFER: '계좌이체',
    OTHER: '기타',
  }
  return labels[kind] || kind
}

function toggleEditMode() {
  if (!isEditMode.value) {
    workingCategories.value = cloneCategories(props.managementCategories)
    workingPaymentMethods.value = clonePaymentMethods(props.managementPaymentMethods)
  }
  finishDrag()
  isEditMode.value = !isEditMode.value
}

function emitGroupToggle(group) {
  emit(isActive(group) ? 'deactivate-group' : 'activate-group', group.id)
}

function emitDetailToggle(detail) {
  emit(isActive(detail) ? 'deactivate-detail' : 'activate-detail', detail.id)
}

function emitPaymentToggle(payment) {
  emit(isActive(payment) ? 'deactivate-payment' : 'activate-payment', payment.id)
}

function finishDrag() {
  dragState.type = ''
  dragState.sourceId = ''
  dragState.sourceGroupId = ''
  dragState.overKey = ''
  dragState.position = ''
}

function startDrag(event, type, itemId, groupId = '') {
  if (!isEditMode.value || props.isSubmitting) {
    event.preventDefault()
    return
  }

  dragState.type = type
  dragState.sourceId = String(itemId)
  dragState.sourceGroupId = groupId == null ? '' : String(groupId)
  dragState.overKey = ''
  dragState.position = ''
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', `${type}:${dragState.sourceId}`)
  }
}

function dropKey(type, itemId, groupId = '') {
  return `${type}:${groupId == null ? '' : String(groupId)}:${String(itemId)}`
}

function updateDropTarget(event, type, itemId, groupId = '') {
  if (dragState.type !== type || (type === 'details' && dragState.sourceGroupId !== String(groupId))) {
    return
  }

  const rect = event.currentTarget.getBoundingClientRect()
  const axisValue = type === 'groups' ? event.clientY : event.clientX
  const axisStart = type === 'groups' ? rect.top : rect.left
  const axisSize = type === 'groups' ? rect.height : rect.width
  dragState.overKey = dropKey(type, itemId, groupId)
  dragState.position = axisValue < axisStart + (axisSize / 2) ? 'before' : 'after'
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
}

function isDropTarget(type, itemId, position, groupId = '') {
  return dragState.type === type
    && dragState.overKey === dropKey(type, itemId, groupId)
    && dragState.position === position
}

function reorderByDrop(items, sourceId, targetId, position) {
  const currentItems = Array.isArray(items) ? [...items] : []
  const sourceIndex = currentItems.findIndex((item) => String(item.id) === String(sourceId))
  const targetIndex = currentItems.findIndex((item) => String(item.id) === String(targetId))
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) {
    return null
  }

  const [source] = currentItems.splice(sourceIndex, 1)
  const nextTargetIndex = currentItems.findIndex((item) => String(item.id) === String(targetId))
  const insertIndex = position === 'after' ? nextTargetIndex + 1 : nextTargetIndex
  currentItems.splice(insertIndex, 0, source)
  return currentItems
}

function dropGroup(group) {
  if (dragState.type !== 'groups') {
    return
  }
  const nextItems = reorderByDrop(catalogCategories.value, dragState.sourceId, group.id, dragState.position)
  if (nextItems) {
    workingCategories.value = cloneCategories(nextItems)
    emit('reorder-groups', { orderedIds: nextItems.map((item) => item.id) })
  }
  finishDrag()
}

function dropDetail(group, detail) {
  if (dragState.type !== 'details' || dragState.sourceGroupId !== String(group.id)) {
    if (dragState.type !== 'details') {
      return
    }
    finishDrag()
    return
  }
  const nextDetails = reorderByDrop(group.details, dragState.sourceId, detail.id, dragState.position)
  if (nextDetails) {
    workingCategories.value = workingCategories.value.map((item) => (
      String(item.id) === String(group.id) ? { ...item, details: nextDetails } : item
    ))
    emit('reorder-details', { groupId: group.id, orderedIds: nextDetails.map((item) => item.id) })
  }
  finishDrag()
}

function dropPayment(payment) {
  if (dragState.type !== 'payments') {
    return
  }
  const nextItems = reorderByDrop(catalogPaymentMethods.value, dragState.sourceId, payment.id, dragState.position)
  if (nextItems) {
    workingPaymentMethods.value = clonePaymentMethods(nextItems)
    emit('reorder-payments', { orderedIds: nextItems.map((item) => item.id) })
  }
  finishDrag()
}
</script>

<template>
  <section class="panel">
    <div class="panel__header">
      <div>
        <h2>분류 관리</h2>
        <p>
          수입/지출 카테고리와 결제수단을 계정별로 정리합니다.
          <template v-if="isEditMode"> ⠿ 핸들을 잡고 원하는 위치에 놓으면 표시 순서가 즉시 저장됩니다.</template>
        </p>
      </div>
      <button class="button button--ghost management-edit-toggle" type="button" @click="toggleEditMode">
        {{ editModeLabel }}
      </button>
    </div>

    <div class="manager-grid">
      <article class="manager-card">
        <h3>대분류 추가</h3>
        <div class="stack-form">
          <select v-model="groupForm.entryType">
            <option value="EXPENSE">지출</option>
            <option value="INCOME">수입</option>
          </select>
          <input v-model="groupForm.name" type="text" placeholder="예: 식비" />
          <input v-model="groupForm.displayOrder" type="number" min="0" placeholder="순서" />
          <button class="button" :disabled="isSubmitting" @click="emit('create-group')">
            {{ isSubmitting && activeSubmit === 'group' ? '추가 중...' : '대분류 추가' }}
          </button>
        </div>
      </article>

      <article class="manager-card">
        <h3>소분류 추가</h3>
        <div class="stack-form">
          <select v-model="detailForm.groupId">
            <option v-for="group in categories" :key="group.id" :value="String(group.id)">
              {{ entryTypeLabel(group.entryType) }} / {{ group.name }}
            </option>
          </select>
          <input v-model="detailForm.name" type="text" placeholder="예: 군것질" />
          <input v-model="detailForm.displayOrder" type="number" min="0" placeholder="순서" />
          <button class="button" :disabled="isSubmitting" @click="emit('create-detail')">
            {{ isSubmitting && activeSubmit === 'detail' ? '추가 중...' : '소분류 추가' }}
          </button>
        </div>
      </article>

      <article class="manager-card">
        <h3>결제수단 추가</h3>
        <div class="stack-form">
          <select v-model="paymentForm.kind">
            <option value="CARD">카드</option>
            <option value="CASH">현금</option>
            <option value="POINT">포인트</option>
            <option value="TRANSFER">계좌이체</option>
            <option value="OTHER">기타</option>
          </select>
          <input v-model="paymentForm.name" type="text" placeholder="예: 토스카드" />
          <input v-model="paymentForm.displayOrder" type="number" min="0" placeholder="순서" />
          <button class="button" :disabled="isSubmitting" @click="emit('create-payment')">
            {{ isSubmitting && activeSubmit === 'payment' ? '추가 중...' : '결제수단 추가' }}
          </button>
        </div>
      </article>
    </div>

    <div class="catalog">
      <article
        v-for="group in catalogCategories"
        :key="group.id"
        class="catalog__group"
        :class="{
          'catalog__group--inactive': !isActive(group),
          'catalog__group--dragging': dragState.type === 'groups' && dragState.sourceId === String(group.id),
          'catalog__group--drop-before': isDropTarget('groups', group.id, 'before'),
          'catalog__group--drop-after': isDropTarget('groups', group.id, 'after'),
        }"
        @dragover.prevent="updateDropTarget($event, 'groups', group.id)"
        @drop.prevent="dropGroup(group)"
      >
        <div class="catalog__head">
          <strong class="catalog__title">
            <span
              v-if="isEditMode"
              class="catalog-drag-handle"
              draggable="true"
              role="button"
              tabindex="0"
              :aria-label="`${group.name} 대분류 순서 이동`"
              title="잡고 원하는 위치에 놓으세요"
              @dragstart="startDrag($event, 'groups', group.id)"
              @dragend="finishDrag"
            >
              ⠿
            </span>
            {{ entryTypeLabel(group.entryType) }} / {{ group.name }}
            <span v-if="!isActive(group)" class="catalog__status">숨김</span>
          </strong>
          <div v-if="isEditMode" class="catalog__actions">
            <button
              class="button button--ghost"
              type="button"
              :disabled="isSubmitting"
              @click="emitGroupToggle(group)"
            >
              {{ isActive(group) ? '비활성화' : '복구' }}
            </button>
            <button
              class="button button--danger"
              type="button"
              :disabled="isSubmitting"
              @click="emit('delete-group', group)"
            >
              삭제
            </button>
          </div>
        </div>
        <div class="catalog__chips" :class="{ 'catalog__chips--editable': isEditMode }">
          <template v-if="group.details?.length">
            <template v-if="isEditMode">
              <div
                v-for="detail in group.details"
                :key="detail.id"
                class="catalog__sortable-item"
                :class="{
                  'catalog__sortable-item--inactive': !isActive(detail),
                  'catalog__sortable-item--dragging': dragState.type === 'details' && dragState.sourceId === String(detail.id),
                  'catalog__sortable-item--drop-before': isDropTarget('details', detail.id, 'before', group.id),
                  'catalog__sortable-item--drop-after': isDropTarget('details', detail.id, 'after', group.id),
                }"
                @dragover.prevent="updateDropTarget($event, 'details', detail.id, group.id)"
                @drop.prevent="dropDetail(group, detail)"
              >
                <span
                  class="catalog-drag-handle"
                  draggable="true"
                  role="button"
                  tabindex="0"
                  :aria-label="`${detail.name} 소분류 순서 이동`"
                  title="잡고 원하는 위치에 놓으세요"
                  @dragstart="startDrag($event, 'details', detail.id, group.id)"
                  @dragend="finishDrag"
                >
                  ⠿
                </span>
                <span class="catalog__sortable-name">{{ detail.name }}</span>
                <span v-if="!isActive(detail)" class="catalog__status">숨김</span>
                <div class="catalog__sortable-actions">
                  <button
                    class="catalog-chip__action"
                    type="button"
                    :disabled="isSubmitting"
                    @click="emitDetailToggle(detail)"
                  >
                    {{ isActive(detail) ? '숨김' : '복구' }}
                  </button>
                  <button
                    class="catalog-chip__action catalog-chip__action--danger"
                    type="button"
                    :disabled="isSubmitting"
                    @click="emit('delete-detail', { detail, group })"
                  >
                    삭제
                  </button>
                </div>
              </div>
            </template>
            <template v-else>
              <span
                v-for="detail in group.details"
                :key="detail.id"
                class="chip chip--neutral catalog-chip"
              >
                {{ detail.name }}
              </span>
            </template>
          </template>
          <span v-else class="catalog__empty">소분류 없음</span>
        </div>
      </article>

      <article
        class="catalog__group"
        :class="{ 'catalog__group--inactive': isEditMode && !catalogPaymentMethods.some(isActive) }"
      >
        <div class="catalog__head">
          <strong>결제수단</strong>
        </div>
        <div class="catalog__chips" :class="{ 'catalog__chips--editable': isEditMode }">
          <template v-if="catalogPaymentMethods.length">
            <template v-if="isEditMode">
              <div
                v-for="payment in catalogPaymentMethods"
                :key="payment.id"
                class="catalog__sortable-item"
                :class="{
                  'catalog__sortable-item--inactive': !isActive(payment),
                  'catalog__sortable-item--dragging': dragState.type === 'payments' && dragState.sourceId === String(payment.id),
                  'catalog__sortable-item--drop-before': isDropTarget('payments', payment.id, 'before'),
                  'catalog__sortable-item--drop-after': isDropTarget('payments', payment.id, 'after'),
                }"
                @dragover.prevent="updateDropTarget($event, 'payments', payment.id)"
                @drop.prevent="dropPayment(payment)"
              >
                <span
                  class="catalog-drag-handle"
                  draggable="true"
                  role="button"
                  tabindex="0"
                  :aria-label="`${payment.name} 결제수단 순서 이동`"
                  title="잡고 원하는 위치에 놓으세요"
                  @dragstart="startDrag($event, 'payments', payment.id)"
                  @dragend="finishDrag"
                >
                  ⠿
                </span>
                <span class="catalog__sortable-name">{{ payment.name }} / {{ paymentKindLabel(payment.kind) }}</span>
                <span v-if="!isActive(payment)" class="catalog__status">숨김</span>
                <div class="catalog__sortable-actions">
                  <button
                    class="catalog-chip__action"
                    type="button"
                    :disabled="isSubmitting"
                    @click="emitPaymentToggle(payment)"
                  >
                    {{ isActive(payment) ? '숨김' : '복구' }}
                  </button>
                  <button
                    class="catalog-chip__action catalog-chip__action--danger"
                    type="button"
                    :disabled="isSubmitting"
                    @click="emit('delete-payment', payment)"
                  >
                    삭제
                  </button>
                </div>
              </div>
            </template>
            <template v-else>
              <span
                v-for="payment in catalogPaymentMethods"
                :key="payment.id"
                class="chip chip--neutral catalog-chip"
              >
                {{ payment.name }} / {{ paymentKindLabel(payment.kind) }}
              </span>
            </template>
          </template>
          <span v-else class="catalog__empty">결제수단 없음</span>
        </div>
      </article>
    </div>
  </section>
</template>

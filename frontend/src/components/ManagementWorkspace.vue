<script setup>
import { computed, ref } from 'vue'

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

const catalogCategories = computed(() => (
  isEditMode.value ? props.managementCategories : props.categories
))

const catalogPaymentMethods = computed(() => (
  isEditMode.value ? props.managementPaymentMethods : props.paymentMethods
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

function moveItem(items, itemId, direction) {
  const nextItems = Array.isArray(items) ? [...items] : []
  const currentIndex = nextItems.findIndex((item) => String(item.id) === String(itemId))
  const targetIndex = currentIndex + direction
  if (currentIndex < 0 || targetIndex < 0 || targetIndex >= nextItems.length) {
    return null
  }

  const [item] = nextItems.splice(currentIndex, 1)
  nextItems.splice(targetIndex, 0, item)
  return nextItems
}

function isFirstItem(items, itemId) {
  return !Array.isArray(items) || items.findIndex((item) => String(item.id) === String(itemId)) <= 0
}

function isLastItem(items, itemId) {
  if (!Array.isArray(items)) {
    return true
  }
  const currentIndex = items.findIndex((item) => String(item.id) === String(itemId))
  return currentIndex < 0 || currentIndex === items.length - 1
}

function moveGroup(group, direction) {
  const nextItems = moveItem(catalogCategories.value, group.id, direction)
  if (nextItems) {
    emit('reorder-groups', { orderedIds: nextItems.map((item) => item.id) })
  }
}

function moveDetail(group, detail, direction) {
  const nextItems = moveItem(group.details, detail.id, direction)
  if (nextItems) {
    emit('reorder-details', { groupId: group.id, orderedIds: nextItems.map((item) => item.id) })
  }
}

function movePayment(payment, direction) {
  const nextItems = moveItem(catalogPaymentMethods.value, payment.id, direction)
  if (nextItems) {
    emit('reorder-payments', { orderedIds: nextItems.map((item) => item.id) })
  }
}
</script>

<template>
  <section class="panel">
    <div class="panel__header">
      <div>
        <h2>분류 관리</h2>
        <p>
          수입/지출 카테고리와 결제수단을 계정별로 정리합니다.
          <template v-if="isEditMode"> 위·아래 버튼으로 표시 순서를 바꾸면 즉시 저장됩니다.</template>
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
        :class="{ 'catalog__group--inactive': !isActive(group) }"
      >
        <div class="catalog__head">
          <strong class="catalog__title">
            {{ entryTypeLabel(group.entryType) }} / {{ group.name }}
            <span v-if="!isActive(group)" class="catalog__status">숨김</span>
          </strong>
          <div v-if="isEditMode" class="catalog__actions">
            <div class="catalog__order-actions" aria-label="대분류 표시 순서 조정">
              <span class="catalog__order-label">순서</span>
              <button
                class="button button--ghost catalog__order-button"
                type="button"
                :disabled="isSubmitting || isFirstItem(catalogCategories, group.id)"
                :aria-label="`${group.name} 위로 이동`"
                @click="moveGroup(group, -1)"
              >
                ↑
              </button>
              <button
                class="button button--ghost catalog__order-button"
                type="button"
                :disabled="isSubmitting || isLastItem(catalogCategories, group.id)"
                :aria-label="`${group.name} 아래로 이동`"
                @click="moveGroup(group, 1)"
              >
                ↓
              </button>
            </div>
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
        <div class="catalog__chips">
          <template v-if="group.details?.length">
            <template v-if="isEditMode">
              <span
                v-for="detail in group.details"
                :key="detail.id"
                class="chip chip--neutral catalog-chip catalog-chip--editable"
                :class="{ 'catalog-chip--inactive': !isActive(detail) }"
              >
                <span>{{ detail.name }}</span>
                <button
                  class="catalog-chip__action catalog-chip__order"
                  type="button"
                  :disabled="isSubmitting || isFirstItem(group.details, detail.id)"
                  :aria-label="`${detail.name} 위로 이동`"
                  @click="moveDetail(group, detail, -1)"
                >
                  ↑
                </button>
                <button
                  class="catalog-chip__action catalog-chip__order"
                  type="button"
                  :disabled="isSubmitting || isLastItem(group.details, detail.id)"
                  :aria-label="`${detail.name} 아래로 이동`"
                  @click="moveDetail(group, detail, 1)"
                >
                  ↓
                </button>
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
              </span>
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
        <div class="catalog__chips">
          <template v-if="catalogPaymentMethods.length">
            <template v-if="isEditMode">
              <span
                v-for="payment in catalogPaymentMethods"
                :key="payment.id"
                class="chip chip--neutral catalog-chip catalog-chip--editable"
                :class="{ 'catalog-chip--inactive': !isActive(payment) }"
              >
                <span>{{ payment.name }} / {{ paymentKindLabel(payment.kind) }}</span>
                <button
                  class="catalog-chip__action catalog-chip__order"
                  type="button"
                  :disabled="isSubmitting || isFirstItem(catalogPaymentMethods, payment.id)"
                  :aria-label="`${payment.name} 위로 이동`"
                  @click="movePayment(payment, -1)"
                >
                  ↑
                </button>
                <button
                  class="catalog-chip__action catalog-chip__order"
                  type="button"
                  :disabled="isSubmitting || isLastItem(catalogPaymentMethods, payment.id)"
                  :aria-label="`${payment.name} 아래로 이동`"
                  @click="movePayment(payment, 1)"
                >
                  ↓
                </button>
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
              </span>
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

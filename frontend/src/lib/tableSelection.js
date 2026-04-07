import { computed, ref, watch } from 'vue'

function normalizeSelectionId(value) {
  if (value === null || value === undefined) {
    return ''
  }
  return String(value)
}

export function useTableSelection(itemsRef, getId = (item) => item?.id) {
  const selectedIds = ref([])

  const visibleIds = computed(() =>
    (itemsRef.value ?? [])
      .map((item) => normalizeSelectionId(getId(item)))
      .filter(Boolean),
  )

  watch(
    visibleIds,
    (ids) => {
      const visibleIdSet = new Set(ids)
      selectedIds.value = selectedIds.value.filter((id) => visibleIdSet.has(id))
    },
    { immediate: true },
  )

  const allVisibleSelected = computed(() =>
    visibleIds.value.length > 0
    && visibleIds.value.every((id) => selectedIds.value.includes(id)),
  )

  const someVisibleSelected = computed(() =>
    selectedIds.value.length > 0 && !allVisibleSelected.value,
  )

  function isSelected(item) {
    return selectedIds.value.includes(normalizeSelectionId(getId(item)))
  }

  function toggleItem(item) {
    const id = normalizeSelectionId(getId(item))
    if (!id) {
      return
    }
    if (selectedIds.value.includes(id)) {
      selectedIds.value = selectedIds.value.filter((currentId) => currentId !== id)
      return
    }
    selectedIds.value = [...selectedIds.value, id]
  }

  function toggleAllVisible() {
    if (allVisibleSelected.value) {
      selectedIds.value = []
      return
    }
    selectedIds.value = [...visibleIds.value]
  }

  function clearSelection() {
    selectedIds.value = []
  }

  return {
    selectedIds,
    allVisibleSelected,
    someVisibleSelected,
    isSelected,
    toggleItem,
    toggleAllVisible,
    clearSelection,
  }
}

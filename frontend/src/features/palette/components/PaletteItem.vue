<script setup>
const props = defineProps({
  config: {
    type: Object,
    required: true,
  },
  definition: {
    type: Object,
    required: true,
  },
  data: {
    type: Object,
    default: () => ({}),
  },
  title: {
    type: String,
    default: '',
  },
  editMode: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['resize', 'hide', 'remove'])

function handleResize(event) {
  emit('resize', props.config.id, event.target.value)
}
</script>

<template>
  <article class="palette-item" :class="{ 'palette-item--editing': editMode }">
    <header class="palette-item__toolbar">
      <strong>{{ title }}</strong>
      <div v-if="editMode" class="palette-item__actions" data-no-drag="true">
        <select :value="config.size" aria-label="팔레트 크기" @change="handleResize">
          <option v-for="size in definition.supportedSizes" :key="size" :value="size">
            {{ size }}
          </option>
        </select>
        <button type="button" @click="emit('hide', config.id)">숨김</button>
        <button type="button" @click="emit('remove', config.id)">삭제</button>
      </div>
    </header>

    <div class="palette-item__body">
      <component
        :is="definition.component"
        :config="config"
        :data="data"
      />
    </div>
  </article>
</template>

<style scoped>
.palette-item {
  background: #ffffff;
  border: 1px solid #d9dde5;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.05);
  color: #111827;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.palette-item--editing {
  cursor: grab;
  outline: 1px dashed rgba(111, 66, 193, 0.35);
}

.palette-item--editing:active {
  cursor: grabbing;
}

.palette-item__toolbar {
  align-items: center;
  border-bottom: 1px solid #edf0f4;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-height: 30px;
  min-width: 0;
  padding: 6px 8px;
}

.palette-item__toolbar strong {
  color: #1f2937;
  font-size: 0.78rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-item__actions {
  align-items: center;
  display: flex;
  flex: 0 0 auto;
  gap: 4px;
}

.palette-item__actions select,
.palette-item__actions button {
  background: #f8fafc;
  border: 1px solid #d1d5db;
  color: #374151;
  font-size: 0.68rem;
  height: 24px;
  padding: 0 6px;
}

.palette-item__actions button:hover,
.palette-item__actions select:focus {
  border-color: #6f42c1;
  outline: none;
}

.palette-item__body {
  min-height: 0;
  overflow: hidden;
  padding: 8px;
}

:global(:root[data-theme='toss']) .palette-item {
  background: linear-gradient(180deg, rgba(24, 29, 38, 0.98), rgba(18, 23, 31, 0.98));
  border-color: rgba(78, 95, 125, 0.42);
  box-shadow: 0 18px 34px rgba(0, 0, 0, 0.26);
  color: var(--text, #f3f7ff);
}

:global(:root[data-theme='toss']) .palette-item--editing {
  outline-color: rgba(185, 197, 255, 0.34);
}

:global(:root[data-theme='toss']) .palette-item__toolbar {
  border-bottom-color: rgba(78, 95, 125, 0.36);
}

:global(:root[data-theme='toss']) .palette-item__toolbar strong {
  color: var(--text, #f3f7ff);
}

:global(:root[data-theme='toss']) .palette-item__actions select,
:global(:root[data-theme='toss']) .palette-item__actions button {
  background: rgba(27, 33, 44, 0.96);
  border-color: rgba(78, 95, 125, 0.46);
  color: var(--text, #f3f7ff);
}

:global(:root[data-theme='toss']) .palette-item__actions button:hover,
:global(:root[data-theme='toss']) .palette-item__actions select:focus {
  border-color: rgba(185, 239, 53, 0.42);
}

@media (max-width: 720px) {
  .palette-item__actions {
    gap: 3px;
  }

  .palette-item__actions button {
    max-width: 42px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
</style>

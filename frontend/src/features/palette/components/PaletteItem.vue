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
  background: var(--household-dash-card, #ffffff);
  border: 1px solid var(--household-dash-line, #d9dde5);
  border-radius: 18px;
  box-shadow: var(--household-dash-shadow, 0 18px 46px rgba(0, 83, 77, 0.08));
  color: var(--household-dash-ink, #111827);
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.palette-item--editing {
  cursor: grab;
  outline: 1px dashed rgba(0, 105, 96, 0.38);
}

.palette-item--editing:active {
  cursor: grabbing;
}

.palette-item__toolbar {
  align-items: center;
  border-bottom: 1px solid var(--household-dash-line, #edf0f4);
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-height: 30px;
  min-width: 0;
  padding: 6px 8px;
}

.palette-item__toolbar strong {
  color: var(--household-dash-ink, #1f2937);
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
  gap: 6px;
}

.palette-item__actions select,
.palette-item__actions button {
  background: var(--household-dash-control, #f8fafc);
  border: 1px solid var(--household-dash-line, #d1d5db);
  border-radius: 8px;
  color: var(--household-dash-ink, #374151);
  font-size: 0.72rem;
  font-weight: 800;
  height: 34px;
  min-width: 44px;
  padding: 0 10px;
  white-space: nowrap;
}

.palette-item__actions select {
  min-width: 72px;
}

.palette-item__actions button:hover,
.palette-item__actions select:focus {
  border-color: var(--household-dash-teal, #006960);
  outline: none;
}

.palette-item__body {
  min-height: 0;
  overflow: hidden;
  padding: 8px;
}

:global(html[data-theme='toss'] .palette-item) {
  background: var(--household-dash-card, linear-gradient(180deg, rgba(31, 38, 50, 0.97), rgba(24, 31, 42, 0.97)));
  border-color: var(--household-dash-line, rgba(91, 107, 129, 0.34));
  box-shadow: var(--household-dash-shadow, 0 18px 34px rgba(0, 0, 0, 0.24));
  color: var(--household-dash-ink, #edf3f8);
}

:global(html[data-theme='toss'] .palette-item--editing) {
  outline-color: rgba(120, 201, 192, 0.3);
}

:global(html[data-theme='toss'] .palette-item__toolbar) {
  border-bottom-color: var(--household-dash-line, rgba(91, 107, 129, 0.32));
}

:global(html[data-theme='toss'] .palette-item__toolbar strong) {
  color: var(--household-dash-ink, #edf3f8);
}

:global(html[data-theme='toss'] .palette-item__actions select),
:global(html[data-theme='toss'] .palette-item__actions button) {
  background: var(--household-dash-control, rgba(33, 41, 54, 0.94));
  border-color: var(--household-dash-line, rgba(91, 107, 129, 0.36));
  color: var(--household-dash-ink, #edf3f8);
}

:global(html[data-theme='toss'] .palette-item__actions button:hover),
:global(html[data-theme='toss'] .palette-item__actions select:focus) {
  border-color: rgba(120, 201, 192, 0.3);
}

@media (max-width: 720px) {
  .palette-item__actions {
    gap: 3px;
  }

  .palette-item__actions button {
    min-width: 42px;
    padding-inline: 8px;
  }
}
</style>

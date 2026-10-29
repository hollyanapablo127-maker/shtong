<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

type Option = { code: string; name: string }
const props = withDefaults(defineProps<{
  modelValue: string
  options: Option[]
  placeholder?: string
  disabled?: boolean
  ariaLabel?: string
}>(), { placeholder: '请选择', disabled: false, ariaLabel: '请选择' })
const emit = defineEmits<{ (event: 'update:modelValue', value: string): void; (event: 'change'): void }>()
const open = ref(false)
const query = ref('')
const root = ref<HTMLElement>()
const selected = computed(() => props.options.find(option => option.code === props.modelValue))
const filtered = computed(() => {
  const value = query.value.trim().toLowerCase()
  return value ? props.options.filter(option => option.name.toLowerCase().includes(value)) : props.options
})
function choose(option: Option) { emit('update:modelValue', option.code); emit('change'); query.value = ''; open.value = false }
function syncQuery() { query.value = selected.value?.name || '' }
function updateQuery(event: Event) { query.value = (event.target as HTMLInputElement).value; open.value = true }
function close(event: MouseEvent) { if (root.value && !root.value.contains(event.target as Node)) open.value = false }
onMounted(() => document.addEventListener('mousedown', close))
onBeforeUnmount(() => document.removeEventListener('mousedown', close))
</script>

<template>
  <div ref="root" class="searchable-select" :class="{ 'is-open': open, 'is-disabled': disabled }">
    <input
      :value="open ? query : (selected?.name || '')"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      :disabled="disabled"
      role="combobox"
      aria-autocomplete="list"
      :aria-expanded="open"
      @focus="syncQuery(); open = true"
      @input="updateQuery"
      @keydown.esc="open = false"
      @keydown.enter.prevent="filtered[0] && choose(filtered[0])"
    >
    <button type="button" class="searchable-select-toggle" :disabled="disabled" tabindex="-1" aria-label="展开选项" @mousedown.prevent="open = !open; if (open) syncQuery()">⌄</button>
    <div v-if="open" class="searchable-select-menu" role="listbox">
      <button v-for="option in filtered" :key="option.code" type="button" role="option" :aria-selected="option.code === modelValue" @mousedown.prevent="choose(option)">{{ option.name }}</button>
      <span v-if="!filtered.length" class="searchable-select-empty">没有匹配项</span>
    </div>
  </div>
</template>

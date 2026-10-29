<script setup lang="ts">
const props = defineProps<{ error?: string; notice?: string; busy?: boolean }>()
</script>
<template><p v-if="error" class="error feedback" role="alert">{{error}}</p><p v-if="notice" class="notice" role="status">{{notice}}</p><p v-if="busy" role="status" class="muted">正在处理，请稍候…</p></template>

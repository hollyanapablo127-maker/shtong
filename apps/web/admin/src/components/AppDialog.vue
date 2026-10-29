<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {X} from 'lucide-vue-next'
defineProps<{title:string;busy?:boolean}>()
const emit=defineEmits<{close:[]}>(),dialog=ref<HTMLDialogElement>()
onMounted(()=>{
  const element=dialog.value
  if(!element)return
  if(typeof element.showModal==='function')element.showModal()
  else element.open=true
})
</script>
<template><dialog ref="dialog" class="modal native-dialog" :aria-label="title" @cancel.prevent="!busy&&emit('close')"><header><h2>{{title}}</h2><button type="button" class="icon-button" :disabled="busy" aria-label="关闭" @click="emit('close')"><X :size="20" aria-hidden="true"/></button></header><slot/></dialog></template>

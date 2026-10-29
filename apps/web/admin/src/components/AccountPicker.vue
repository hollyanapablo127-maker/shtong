<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import type {AccountPage,AccountSummary,ShtongApi} from '@shtong/api-sdk'
import {errorMessage} from '@shtong/web-shared'
const props=defineProps<{api:ShtongApi;modelValue:string;label?:string;disabled?:boolean}>()
const emit=defineEmits<{'update:modelValue':[string];selected:[AccountSummary|undefined]}>()
const query=ref(''),rows=ref<AccountSummary[]>([]),busy=ref(false),error=ref(''),next=ref<string>(),chosen=ref<AccountSummary>()
let revision=0
async function search(more=false){const version=++revision;busy.value=true;error.value='';try{const params=new URLSearchParams({q:query.value,limit:'30'});if(more&&next.value)params.set('cursor',next.value);const page=await props.api.request<AccountPage>(`/system/accounts?${params}`);if(version!==revision)return;rows.value=more?[...rows.value,...page.items]:page.items;next.value=page.nextCursor||undefined}catch(problem){if(version===revision)error.value=errorMessage(problem)}finally{if(version===revision)busy.value=false}}
function choose(row?:AccountSummary){chosen.value=row;emit('update:modelValue',row?.id||'');emit('selected',row)}
watch(()=>props.modelValue,value=>{if(!value)chosen.value=undefined})
onMounted(()=>search())
</script>
<template><fieldset class="account-picker" :disabled="disabled"><legend>{{label||'选择账户'}}</legend><div class="picker-search"><input v-model="query" aria-label="姓名、登录账号或学工号" placeholder="姓名、登录账号或学工号" @keydown.enter.prevent="search()"><button type="button" class="secondary" :disabled="busy" @click="search()">{{busy?'查询中':'查找'}}</button></div><p v-if="error" class="error" role="alert">{{error}}</p><div v-if="chosen" class="chosen-account"><strong>{{chosen.displayName}}</strong> · {{chosen.loginName}} · {{chosen.personNo}}<button type="button" class="text-button" @click="choose()">重新选择</button></div><div v-else class="picker-options"><button v-for="row in rows" :key="row.id" type="button" class="picker-option" @click="choose(row)"><strong>{{row.displayName}}</strong><span>{{row.loginName}} · {{row.personNo}}</span></button><p v-if="!rows.length&&!busy" class="empty">没有匹配账户</p><button v-if="next" type="button" class="secondary" :disabled="busy" @click="search(true)">更多账户</button></div></fieldset></template>

<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {ShieldPlus,ShieldX} from 'lucide-vue-next'
import type {AccountSummary,OrgUnit,RoleAssignment,RoleCode,ShtongApi} from '@shtong/api-sdk'
import {orgPath,roleCodes,zh} from '@shtong/web-shared'
import {useAction} from '../composables/useAction'
import AccountPicker from './AccountPicker.vue'
import ScopePicker from './ScopePicker.vue'
import ActionFeedback from './ActionFeedback.vue'
import AppDialog from './AppDialog.vue'
const props=defineProps<{api:ShtongApi}>(),accountId=ref(''),account=ref<AccountSummary>(),assignments=ref<RoleAssignment[]>([]),units=ref<OrgUnit[]>([]),role=ref<RoleCode>('TEACHER_ADMIN'),scopes=ref<string[]>([]),reason=ref('调整账户身份与管理范围'),revoking=ref<RoleAssignment>()
const {busy,error,notice,run}=useAction()
async function load(){assignments.value=accountId.value?await props.api.request<RoleAssignment[]>(`/system/accounts/${accountId.value}/role-assignments`):[]}
function choose(value?:AccountSummary){account.value=value;assignments.value=[];void run(load)}
function add(){void run(async()=>{if(!accountId.value){error.value='请先选择账户';return}if(['TEACHER_ADMIN','STUDENT_ADMIN','MEDICAL_INSURANCE_ADMIN'].includes(role.value)&&!scopes.value.length){error.value='请至少选择一个管理范围';return}const result=await props.api.request<Record<string,unknown>>(`/system/accounts/${accountId.value}/role-assignments`,{method:'POST',body:JSON.stringify({roleCode:role.value,scopeCeilingOrgUnitIds:scopes.value,validFrom:new Date().toISOString(),validTo:null,reason:reason.value})});notice.value=result.state==='PENDING_APPROVAL'?'已提交，等待另一位管理员在“安全复核”中确认。':'身份已添加，账户重新登录后生效';await load()})}
function revoke(){void run(async()=>{if(!revoking.value)return;const result=await props.api.request<Record<string,unknown>>(`/system/role-assignments/${revoking.value.id}/revoke`,{method:'POST',body:JSON.stringify({reason:reason.value})});revoking.value=undefined;notice.value=result?.state==='PENDING_APPROVAL'?'已提交取消申请，等待另一位管理员确认。':'身份已取消';await load()})}
onMounted(()=>run(async()=>{units.value=await props.api.request<OrgUnit[]>('/system/org-units')}))
</script>
<template><section class="view"><header class="page-header"><h1>身份与权限</h1></header><AccountPicker v-model="accountId" :api="api" :disabled="busy" @selected="choose"/><ActionFeedback :error="error" :notice="notice" :busy="busy"/>
  <template v-if="account"><h2>{{account.displayName}}的现有身份</h2><div class="record-list"><article v-for="item in assignments" :key="item.id"><div><strong>{{zh(item.roleCode)}}</strong> <span class="status">{{zh(item.status)}}</span><p>{{item.scopeCeilings.map(scope=>orgPath(scope.id,units)).join('；')||(item.roleCode==='SYSTEM_ADMIN'?'系统账户管理': '本人所在班级')}}</p></div><button class="secondary" :disabled="busy||item.status!=='ACTIVE'" @click="error='';revoking=item"><ShieldX :size="18" aria-hidden="true"/>取消身份</button></article><p v-if="!assignments.length" class="empty">暂无身份</p></div>
  <form class="stack-form" @submit.prevent="add"><h2>添加身份</h2><label>身份<select v-model="role"><option v-for="code in roleCodes" :key="code" :value="code" :disabled="assignments.some(item=>item.roleCode===code&&item.status==='ACTIVE')">{{zh(code)}}</option></select></label><ScopePicker v-model="scopes" :units="units" :role="role"/><label>操作原因<input v-model="reason" required minlength="2" maxlength="500"></label><button class="primary" :disabled="busy||assignments.some(item=>item.roleCode===role&&item.status==='ACTIVE')"><ShieldPlus :size="18" aria-hidden="true"/>{{role==='SYSTEM_ADMIN'?'提交管理员复核':'确认添加身份'}}</button></form></template>
  <AppDialog v-if="revoking" title="确认取消身份" :busy="busy" @close="revoking=undefined"><form @submit.prevent="revoke"><p>取消{{account?.displayName}}的“{{zh(revoking.roleCode)}}”身份？该账户现有登录将失效。</p><label>原因<input v-model="reason" required minlength="2" maxlength="500"></label><ActionFeedback :error="error"/><button class="primary danger-action" :disabled="busy">确认取消</button></form></AppDialog>
</section></template>

<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {Building2,Pencil,Plus,Trash2} from 'lucide-vue-next'
import type {OrgUnit,OrgUnitType,ShtongApi} from '@shtong/api-sdk'
import {orgPath,zh} from '@shtong/web-shared'
import {useAction} from '../composables/useAction'
import ActionFeedback from './ActionFeedback.vue'
import AppDialog from './AppDialog.vue'
const props=defineProps<{api:ShtongApi}>(),units=ref<OrgUnit[]>([]),editing=ref<OrgUnit>(),deleteTarget=ref<OrgUnit>(),query=ref('')
const {busy,error,notice,run}=useAction()
const form=reactive({type:'SCHOOL' as OrgUnitType,parentId:'',code:'',name:'',reason:'维护学校与班级'}),editName=ref('')
const parentTypes:Partial<Record<OrgUnitType,OrgUnitType>>={COLLEGE:'SCHOOL',GRADE:'COLLEGE',CLASS:'GRADE'}
const parents=computed(()=>units.value.filter(unit=>unit.type===parentTypes[form.type]&&unit.status==='ACTIVE'))
const visible=computed(()=>units.value.filter(unit=>(orgPath(unit.id,units.value)+unit.code).includes(query.value)).sort((a,b)=>orgPath(a.id,units.value).localeCompare(orgPath(b.id,units.value),'zh-CN')))
watch(()=>form.type,()=>{form.parentId='';error.value=''})
async function load(){units.value=await props.api.request<OrgUnit[]>('/system/org-units')}
function add(){void run(async()=>{await props.api.request('/system/org-units',{method:'POST',body:JSON.stringify({...form,code:form.code.trim()||`ORG-${crypto.randomUUID().slice(0,8)}`,parentId:form.type==='SCHOOL'?null:form.parentId,expectedVersion:0})});notice.value=`${form.name}已创建`;form.code='';form.name='';await load()})}
function openEdit(unit:OrgUnit){error.value='';editing.value=unit;editName.value=unit.name}
function update(){void run(async()=>{if(!editing.value)return;const unit=editing.value;await props.api.request(`/system/org-units/${unit.id}`,{method:'PATCH',body:JSON.stringify({type:unit.type,parentId:unit.parentId||null,code:unit.code,name:editName.value,expectedVersion:unit.version,reason:form.reason})});editing.value=undefined;notice.value='名称已保存';await load()})}
function remove(){void run(async()=>{if(!deleteTarget.value)return;await props.api.request(`/system/org-units/${deleteTarget.value.id}`,{method:'DELETE',body:JSON.stringify({reason:form.reason})});deleteTarget.value=undefined;notice.value='组织已删除，可重新使用原名称和编号创建';await load()})}
onMounted(()=>run(load))
</script>
<template><section class="view"><header class="page-header"><h1>学校与班级</h1></header>
  <form class="tool-panel create-grid" @submit.prevent="add"><label>添加类型<select v-model="form.type"><option v-for="type in ['SCHOOL','COLLEGE','GRADE','CLASS']" :key="type" :value="type">{{zh(type)}}</option></select></label><label v-if="form.type!=='SCHOOL'">所属{{zh(parentTypes[form.type])}}<select v-model="form.parentId" required><option value="" disabled>请选择{{zh(parentTypes[form.type])}}</option><option v-for="unit in parents" :key="unit.id" :value="unit.id">{{orgPath(unit.id,units)}}</option></select></label><label>{{zh(form.type)}}名称<input v-model="form.name" required maxlength="200"></label><label>组织编号（可留空自动生成）<input v-model="form.code" maxlength="100"></label><label>操作原因<input v-model="form.reason" required minlength="2" maxlength="500"></label><button class="primary" :disabled="busy||form.type!=='SCHOOL'&&!parents.length"><Plus :size="18" aria-hidden="true"/>{{busy?'正在创建':'创建'+zh(form.type)}}</button><p v-if="form.type!=='SCHOOL'&&!parents.length" class="error span-2">尚无可选{{zh(parentTypes[form.type])}}，请先创建上一级。</p></form>
  <ActionFeedback :error="error" :notice="notice" :busy="busy"/>
  <label class="reason-control">查找组织<input v-model="query" placeholder="学校、学院、年级、班级名称或编号"></label>
  <div class="org-list"><article v-for="unit in visible" :key="unit.id"><Building2 :size="20" aria-hidden="true"/><div><strong>{{orgPath(unit.id,units)}}</strong><small>{{zh(unit.type)}} · 编号 {{unit.code}}</small></div><span class="status" :class="unit.status.toLowerCase()">{{zh(unit.status)}}</span><div class="row-actions"><button class="icon-button" :disabled="busy" :title="`修改${unit.name}名称`" :aria-label="`修改${unit.name}名称`" @click="openEdit(unit)"><Pencil :size="18" aria-hidden="true"/></button><button class="secondary compact" :disabled="busy" @click="error='';deleteTarget=unit"><Trash2 :size="17" aria-hidden="true"/>删除</button></div></article><p v-if="!visible.length" class="empty">{{busy?'正在加载':'暂无学校与班级'}}</p></div>
  <AppDialog v-if="editing" title="修改名称" :busy="busy" @close="editing=undefined"><form @submit.prevent="update"><label>名称<input v-model="editName" required maxlength="200"></label><label>修改原因<input v-model="form.reason" required minlength="2" maxlength="500"></label><ActionFeedback :error="error"/><button class="primary" :disabled="busy">{{busy?'保存中':'保存'}}</button></form></AppDialog>
  <AppDialog v-if="deleteTarget" title="确认删除组织" :busy="busy" @close="deleteTarget=undefined"><form @submit.prevent="remove"><p>删除“{{orgPath(deleteTarget.id,units)}}”？</p><p>有下级组织、在用账户或管理权限时不能删除。删除成功后可重新使用原名称和编号创建。</p><label>删除原因<input v-model="form.reason" required minlength="2" maxlength="500"></label><ActionFeedback :error="error"/><button class="primary danger-action" :disabled="busy">确认删除</button></form></AppDialog>
</section></template>

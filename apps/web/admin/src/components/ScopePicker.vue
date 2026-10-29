<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import type {OrgUnit,RoleCode} from '@shtong/api-sdk'
import {orgPath,zh} from '@shtong/web-shared'
const props=defineProps<{units:OrgUnit[];role:RoleCode;modelValue:string[]}>()
const emit=defineEmits<{'update:modelValue':[string[]]}>(),query=ref('')
const scoped=computed(()=>props.role==='TEACHER_ADMIN'||props.role==='STUDENT_ADMIN'||props.role==='MEDICAL_INSURANCE_ADMIN')
const options=computed(()=>props.units.filter(unit=>unit.status==='ACTIVE'&&(props.role==='TEACHER_ADMIN'?unit.type==='GRADE':['COLLEGE','GRADE','CLASS'].includes(unit.type))&&orgPath(unit.id,props.units).includes(query.value)))
function toggle(id:string){emit('update:modelValue',props.modelValue.includes(id)?props.modelValue.filter(value=>value!==id):[...props.modelValue,id])}
watch(()=>props.role,()=>emit('update:modelValue',[]))
</script>
<template><fieldset v-if="scoped" class="scope-picker"><legend>管理范围（已选 {{modelValue.length}} 项）</legend><input v-model="query" aria-label="查找管理范围" :placeholder="role==='TEACHER_ADMIN'?'查找年级':'查找学院、年级或班级'"><div class="scope-options"><label v-for="unit in options" :key="unit.id"><input type="checkbox" :checked="modelValue.includes(unit.id)" @change="toggle(unit.id)"><span>{{orgPath(unit.id,units)}} <small>{{zh(unit.type)}}</small></span></label><p v-if="!options.length" class="empty">没有可选范围</p></div></fieldset></template>

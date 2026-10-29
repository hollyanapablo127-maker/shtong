import {presentation} from './presentation.generated'
import type {OrgUnit} from '@shtong/api-sdk'
const labels:Record<string,string>=presentation.labels
export const roleCodes=['USER','TEACHER_ADMIN','STUDENT_ADMIN','MEDICAL_INSURANCE_ADMIN','SYSTEM_ADMIN'] as const
export const eventCodes=Object.keys(labels).filter(key=>key.includes('.'))
export const zh=(value:unknown):string=>value==null?'无':labels[String(value)]||String(value)
export function errorMessage(error:unknown):string {
  const value=error as {code?:string;messageKey?:string}
  const messages:Record<string,string>={ 'error.organization.parent':'请为学院选择学校、为年级选择学院、为班级选择年级；上一级必须处于正常状态。','error.password.policy':'密码至少 8 位，并同时包含大写字母、小写字母和数字。','error.audit.range':'开始和结束时间必须有效，查询范围不能超过31天。' }
  return messages[value?.messageKey||'']||(presentation.errors as Record<string,string>)[value?.code||'']||'操作未完成，请检查网络连接后重试。'
}
export const importIssue=(code:string)=>(presentation.importIssues as Record<string,string>)[code]||'该项有误，请对照模板检查。'
export function orgPath(id:string|null|undefined,units:OrgUnit[]):string {
  if(!id)return '未设置'
  const names:string[]=[],seen=new Set<string>();let unit=units.find(value=>value.id===id)
  if(!unit)return '组织已不可用'
  while(unit&&!seen.has(unit.id)){seen.add(unit.id);names.unshift(unit.name);unit=units.find(value=>value.id===unit?.parentId)}
  return names.join(' / ')
}
export const localTime=(value:string|null|undefined)=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'暂无'
export function saveBlob(blob:Blob,name:string){const url=URL.createObjectURL(blob),anchor=document.createElement('a');anchor.href=url;anchor.download=name;anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}

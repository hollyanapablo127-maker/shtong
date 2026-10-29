<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {ArrowLeft,ArrowRight,Building2,GraduationCap,Check,Clipboard,ClipboardCheck,KeyRound,LayoutDashboard,LogOut,MoreHorizontal,ScrollText,ShieldCheck,ShieldPlus,UserRound,Users,X} from 'lucide-vue-next'
import {createSession,installSessionGuards,errorMessage,saveBlob} from '@shtong/web-shared'
import type {SensitiveProfileField} from '@shtong/api-sdk'
import AuthGate from './components/AuthGate.vue'
import AccountsView from './components/AccountsView.vue'
import AuthorizationView from './components/AuthorizationView.vue'
import OrganizationView from './components/OrganizationView.vue'
import AuditView from './components/AuditView.vue'
import ControlsView from './components/ControlsView.vue'

const session=createSession('WEB_ADMIN'),route=ref('system.accounts'),authSection=ref<'roles'|'controls'>('roles'),showProfile=ref(false),profileMode=ref<'details'|'password'>('details'),showMobileActions=ref(false),notice=ref(''),moduleUrl=ref(''),moduleExitUrl=ref(''),moduleLoading=ref(false),moduleLeaving=ref(false),moduleFrame=ref<HTMLIFrameElement>()
const passwords=reactive({current:'',next:'',confirm:''}),profileDialog=ref<HTMLElement>(),returnFocus=ref<HTMLElement>(),mainContent=ref<HTMLElement>()
const reveal=reactive<{field:SensitiveProfileField|null;label:string;password:string}>({field:null,label:'',password:''})
import administratorGuide from '../../../../docs/manuals/system-administrator-guide.md?raw'
function downloadGuide(){saveBlob(new Blob([administratorGuide],{type:'text/markdown;charset=utf-8'}),'生化通系统管理员使用手册.md')}
const nav=[{code:'system.accounts',label:'账户管理',icon:Users},{code:'system.graduates',label:'毕业生管理',icon:GraduationCap},{code:'system.authorization',label:'身份与权限',icon:ShieldCheck},{code:'system.organization',label:'学校与班级',icon:Building2},{code:'system.audit',label:'操作记录',icon:ScrollText}]
const medicalInsuranceAvailable=computed(()=>session.state.bootstrap?.activeModules.some(module=>module.code==='medical-insurance')===true)
const visibleNav=computed(()=>nav.filter(item=>session.state.bootstrap?.activeModules.some(module=>module.code===item.code)))
const routeAllowed=computed(()=>visibleNav.value.some(item=>item.code===route.value))
let moduleExitResolve: (()=>void)|undefined
let moduleExitTimer: number|undefined
let moduleTransitionId = 0
let removeSessionGuards:undefined|(()=>void)

function normalizeModuleUrl(raw:string){
  const target=new URL(raw,window.location.href),current=new URL(window.location.href)
  if(['localhost','127.0.0.1'].includes(target.hostname)&&target.hostname!==current.hostname)target.hostname=current.hostname
  if(current.protocol==='https:'&&target.protocol==='http:')target.protocol='https:'
  target.searchParams.set('embedded','1')
  return target
}
function sameModulePath(left:string,right:string){
  try{const a=new URL(left,window.location.href),b=new URL(right,window.location.href);return a.origin===b.origin&&a.pathname.replace(/\/+$/,'')===b.pathname.replace(/\/+$/,'')}catch{return false}
}
function completeModuleExit(){
  if(!moduleUrl.value&&!moduleLeaving.value)return
  if(moduleExitTimer!==undefined){window.clearTimeout(moduleExitTimer);moduleExitTimer=undefined}
  const resolve=moduleExitResolve
  moduleExitResolve=undefined
  moduleUrl.value='';moduleExitUrl.value='';moduleLoading.value=false;moduleLeaving.value=false
  route.value=visibleNav.value[0]?.code||'home'
  resolve?.()
}
function onModuleMessage(event:MessageEvent){
  if(event.data?.type!=='shtong:module-exit'||event.data?.status!=='completed')return
  if(event.source!==moduleFrame.value?.contentWindow)return
  if(moduleUrl.value){try{if(event.origin!==new URL(moduleUrl.value).origin)return}catch{return}}
  completeModuleExit()
}
function onModuleLoad(){
  if(moduleLeaving.value&&moduleExitUrl.value&&moduleFrame.value?.src&&sameModulePath(moduleFrame.value.src,moduleExitUrl.value)){completeModuleExit();return}
  moduleLoading.value=false
}
onMounted(async()=>{window.addEventListener('message',onModuleMessage);removeSessionGuards=installSessionGuards(session.refresh);await session.restore();if(visibleNav.value.length)route.value=visibleNav.value[0].code})
onBeforeUnmount(()=>{window.removeEventListener('message',onModuleMessage);removeSessionGuards?.()})
watch(route,()=>nextTick(()=>mainContent.value?.focus()))
async function openProfile(event:MouseEvent){returnFocus.value=event.currentTarget as HTMLElement;showMobileActions.value=false;showProfile.value=true;profileMode.value='details';notice.value='';try{session.state.profile=await session.api.profile()}catch(error){notice.value=errorMessage(error)}await nextTick();profileDialog.value?.focus()}
function closeProfile(){showProfile.value=false;nextTick(()=>returnFocus.value?.focus())}
async function openMedicalInsurance(){
  moduleTransitionId++
  if(moduleExitTimer!==undefined){window.clearTimeout(moduleExitTimer);moduleExitTimer=undefined}
  if(moduleExitResolve){const resolve=moduleExitResolve;moduleExitResolve=undefined;resolve()}
  showMobileActions.value=false
  notice.value=''
  try{const ticket=await session.api.issueMedicalInsuranceTicket();const target=normalizeModuleUrl(ticket.redirectUrl);moduleUrl.value=target.toString();moduleExitUrl.value=new URL('/medical-insurance/exit/',target.origin).toString();moduleLoading.value=true;moduleLeaving.value=false;route.value='medical-insurance'}catch(error){notice.value=errorMessage(error)}
}
async function closeMedicalInsurance(){
  const transitionId=++moduleTransitionId
  const frame=moduleFrame.value,exitUrl=moduleExitUrl.value
  if(!frame||!exitUrl){completeModuleExit();return}
  moduleLeaving.value=true;moduleLoading.value=true
  if(moduleExitTimer!==undefined){window.clearTimeout(moduleExitTimer);moduleExitTimer=undefined}
  await new Promise<void>(resolve=>{
    moduleExitResolve=resolve
    frame.src=exitUrl
    moduleExitTimer=window.setTimeout(()=>{
      if(transitionId===moduleTransitionId) completeModuleExit()
    },2000)
  })
}
function profileKeys(event:KeyboardEvent){if(event.key==='Escape'){closeProfile();return}if(event.key!=='Tab'||!profileDialog.value)return;const focusable=[...profileDialog.value.querySelectorAll<HTMLElement>('button,input,select,textarea,[tabindex]:not([tabindex="-1"])')].filter(element=>!element.hasAttribute('disabled'));if(!focusable.length)return;const first=focusable[0],last=focusable[focusable.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}}
function openPassword(){profileMode.value='password';notice.value='';passwords.current=passwords.next=passwords.confirm=''}
function validPassword(value:string){return value.length>=8&&value.length<=128&&/[A-Z]/.test(value)&&/[a-z]/.test(value)&&/\d/.test(value)}
async function changePassword(){if(passwords.next!==passwords.confirm){notice.value='两次输入的新密码不一致';return}if(!validPassword(passwords.next)){notice.value='密码至少 8 位，并同时包含大写字母、小写字母和数字';return}try{await session.changePassword(passwords.current,passwords.next);notice.value='密码已修改';passwords.current=passwords.next=passwords.confirm='';profileMode.value='details';session.state.profile=await session.api.profile()}catch(error){notice.value=errorMessage(error)}}
function role(value:string){return ({USER:'学生',TEACHER_ADMIN:'教师/辅导员',STUDENT_ADMIN:'学生管理员',MEDICAL_INSURANCE_ADMIN:'医保系统管理员',SYSTEM_ADMIN:'系统管理员'} as Record<string,string>)[value]||value}
function gender(value?:string|null){return value==='MALE'?'男':value==='FEMALE'?'女':value==='OTHER'?'其他':'未填写'}
function orgType(value:string){return ({SCHOOL:'学校',COLLEGE:'学院',GRADE:'年级',CLASS:'班级'} as Record<string,string>)[value]||value}
async function copyText(value:string,label:string){if(!value||value==='—')return;try{await navigator.clipboard.writeText(value);notice.value=`${label}已复制`}catch{notice.value='浏览器未允许复制，请检查剪贴板权限'}}
function requestSensitive(field:SensitiveProfileField,label:string){reveal.field=field;reveal.label=label;reveal.password='';notice.value=''}
function closeReveal(){reveal.field=null;reveal.label='';reveal.password=''}
async function revealAndCopy(){if(!reveal.field||!reveal.password)return;try{const result=await session.api.revealProfileField(reveal.field,reveal.password);await navigator.clipboard.writeText(result.value);notice.value=`${reveal.label}已验证并复制`;closeReveal()}catch(error){notice.value=errorMessage(error)}finally{reveal.password=''}}
</script>

<template>
  <AuthGate v-if="session.state.phase!=='authenticated'" :phase="session.state.phase" :error="session.state.error" @login="session.login" @password="session.changePassword" @logout="session.logout"/>
  <div v-else class="admin-shell" :class="{'module-shell':route==='medical-insurance'}">
    <aside class="sidebar" @keydown.esc="showMobileActions=false"><button class="brand" aria-label="返回管理首页" @click="route=visibleNav[0]?.code||'home'"><span class="brand-mark small">生</span><span><strong>生化通</strong><small>系统管理</small></span></button><nav aria-label="系统管理模块"><button v-for="item in visibleNav" :key="item.code" :class="{active:route===item.code}" :aria-label="item.label" :title="item.label" @click="route=item.code;showMobileActions=false"><component :is="item.icon" :size="19" aria-hidden="true"/><span>{{item.label}}</span></button><button v-if="medicalInsuranceAvailable" :class="{active:route==='medical-insurance'}" aria-label="医保信息系统" title="医保信息系统" @click="openMedicalInsurance"><ShieldPlus :size="19" aria-hidden="true"/><span>医保信息系统</span><ArrowRight :size="14" aria-hidden="true"/></button></nav><div class="sidebar-foot"><button aria-label="下载使用手册" @click="downloadGuide"><ScrollText :size="19" aria-hidden="true"/><span>使用手册</span></button><button aria-label="个人中心" title="个人中心" @click="openProfile"><UserRound :size="19" aria-hidden="true"/><span>个人中心</span></button><button aria-label="退出登录" title="退出登录" @click="session.logout"><LogOut :size="19" aria-hidden="true"/><span>退出登录</span></button></div><button class="mobile-more" aria-controls="mobile-account-actions" :aria-expanded="showMobileActions" aria-label="账户操作" @click="showMobileActions=!showMobileActions"><MoreHorizontal :size="20" aria-hidden="true"/><span>更多</span></button><div v-if="showMobileActions" id="mobile-account-actions" class="mobile-account-actions"><button @click="downloadGuide">使用手册</button><button @click="openProfile"><UserRound :size="19" aria-hidden="true"/><span>个人中心</span></button><button @click="showMobileActions=false;session.logout()"><LogOut :size="19" aria-hidden="true"/><span>退出登录</span></button></div></aside>
    <main ref="mainContent" class="content" tabindex="-1">
      <section v-if="route==='medical-insurance'" class="embedded-module"><div class="embedded-module-bar"><div><p class="overline">工作台模块</p><h1>医保信息系统</h1></div><button class="secondary" :disabled="moduleLeaving" @click="closeMedicalInsurance"><ArrowLeft :size="18" aria-hidden="true"/>{{moduleLeaving?'正在返回':'返回工作台'}}</button></div><div class="embedded-module-frame"><div v-if="moduleLoading" class="embedded-module-loading" role="status">{{moduleLeaving?'正在安全退出医保模块':'正在打开医保信息系统'}}</div><iframe ref="moduleFrame" :key="moduleUrl" :src="moduleUrl" title="医保信息系统" @load="onModuleLoad"/></div></section><AccountsView v-else-if="routeAllowed&&(route==='system.accounts'||route==='system.graduates')" :key="route" :api="session.api" :graduates="route==='system.graduates'"/>
      <template v-else-if="routeAllowed&&route==='system.authorization'"><div class="segment" role="tablist" aria-label="授权视图"><button role="tab" :aria-selected="authSection==='roles'" :class="{active:authSection==='roles'}" @click="authSection='roles'"><ShieldCheck :size="17" aria-hidden="true"/>身份与范围</button><button role="tab" :aria-selected="authSection==='controls'" :class="{active:authSection==='controls'}" @click="authSection='controls'"><ClipboardCheck :size="17" aria-hidden="true"/>安全复核</button></div><AuthorizationView v-if="authSection==='roles'" :api="session.api"/><ControlsView v-else :api="session.api"/></template>
      <OrganizationView v-else-if="routeAllowed&&route==='system.organization'" :api="session.api"/>
      <AuditView v-else-if="routeAllowed&&route==='system.audit'" :api="session.api"/>
      <section v-else class="empty-workspace"><LayoutDashboard :size="32" aria-hidden="true"/><p class="overline">管理员工作台</p><h1>当前没有已开放的业务模块</h1><p></p></section>
    </main>
    <div v-if="showProfile" class="modal-layer" @click.self="closeProfile"><section ref="profileDialog" class="modal" role="dialog" aria-modal="true" aria-labelledby="profile-title" tabindex="-1" @keydown="profileKeys"><header><div><h1 id="profile-title">{{profileMode==='details'?'个人中心':'修改密码'}}</h1></div><button class="icon-button" aria-label="关闭个人中心" @click="closeProfile"><X :size="20" aria-hidden="true"/></button></header><template v-if="profileMode==='details'"><p class="profile-copy-hint"><Clipboard :size="16" aria-hidden="true"/>点击任一资料即可复制</p><dl class="detail-list"><dt>姓名</dt><dd><button class="profile-copy" @click="copyText(session.state.profile?.displayName||'','姓名')">{{session.state.profile?.displayName||'—'}}<Clipboard :size="15" aria-hidden="true"/></button></dd><dt>学号/工号</dt><dd><button class="profile-copy" @click="copyText(session.state.profile?.personNo||'','学号/工号')">{{session.state.profile?.personNo||'—'}}<Clipboard :size="15" aria-hidden="true"/></button></dd><dt>身份</dt><dd><button class="profile-copy" @click="copyText(session.state.profile?.roleCodes.map(role).join('、')||'','身份')">{{session.state.profile?.roleCodes.map(role).join('、')||'—'}}<Clipboard :size="15" aria-hidden="true"/></button></dd><template v-if="session.state.profile?.roleCodes.includes('USER')"><dt>性别</dt><dd><button class="profile-copy" @click="copyText(gender(session.state.profile?.gender),'性别')">{{gender(session.state.profile?.gender)}}<Clipboard :size="15" aria-hidden="true"/></button></dd></template><template v-for="item in session.state.profile?.organizationPath" :key="item.type"><dt>{{orgType(item.type)}}</dt><dd><button class="profile-copy" @click="copyText(item.name,orgType(item.type))">{{item.name}}<Clipboard :size="15" aria-hidden="true"/></button></dd></template><template v-if="session.state.profile?.roleCodes.includes('USER')"><dt>对应辅导员</dt><dd><button class="profile-copy" @click="copyText(session.state.profile?.counselors.map(item=>`${item.displayName}（${item.personNo}）`).join('、')||'暂未匹配','对应辅导员')">{{session.state.profile?.counselors.map(item=>`${item.displayName}（${item.personNo}）`).join('、')||'暂未匹配'}}<Clipboard :size="15" aria-hidden="true"/></button></dd><dt>身份证号</dt><dd><button class="profile-copy" @click="requestSensitive('ID_CARD_NUMBER','身份证号')">{{session.state.profile?.idCardNumberMasked||'—'}}<ShieldCheck :size="15" aria-hidden="true"/></button></dd><dt>手机号</dt><dd><button class="profile-copy" @click="requestSensitive('MOBILE_NUMBER','手机号')">{{session.state.profile?.mobileNumberMasked||'—'}}<ShieldCheck :size="15" aria-hidden="true"/></button></dd><dt>现家庭住址</dt><dd><button class="profile-copy" @click="requestSensitive('CURRENT_ADDRESS','现家庭住址')">{{session.state.profile?.currentAddressMasked||'—'}}<ShieldCheck :size="15" aria-hidden="true"/></button></dd><dt>户籍地址</dt><dd><button class="profile-copy" @click="requestSensitive('HOUSEHOLD_ADDRESS','户籍地址')">{{session.state.profile?.householdAddressMasked||'—'}}<ShieldCheck :size="15" aria-hidden="true"/></button></dd></template><template v-for="assignment in session.state.profile?.managedScopes" :key="assignment.roleCode"><dt>{{role(assignment.roleCode)}}管辖范围</dt><dd><button class="profile-copy" @click="copyText(assignment.scopes.map(item=>item.path).join('、')||'—','管辖范围')">{{assignment.scopes.map(item=>item.path).join('、')||'—'}}<Clipboard :size="15" aria-hidden="true"/></button></dd></template></dl><button class="secondary" @click="openPassword"><KeyRound :size="19" aria-hidden="true"/>修改密码</button></template><template v-else><button class="text-button profile-back" @click="profileMode='details'"><ArrowLeft :size="18" aria-hidden="true"/>返回个人中心</button><form @submit.prevent="changePassword"><label>当前密码<input v-model="passwords.current" type="password" autocomplete="current-password" required></label><label>新密码<input v-model="passwords.next" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><label>确认新密码<input v-model="passwords.confirm" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><button class="primary">确认修改</button></form></template><p v-if="notice" class="notice" role="status">{{notice}}</p></section></div>
    <div v-if="reveal.field" class="modal-layer" @click.self="closeReveal"><form class="modal sensitive-copy-dialog" role="dialog" aria-modal="true" aria-labelledby="sensitive-copy-title" @submit.prevent="revealAndCopy"><header><h1 id="sensitive-copy-title">验证后复制{{reveal.label}}</h1><button type="button" class="icon-button" aria-label="关闭" @click="closeReveal"><X :size="20" aria-hidden="true"/></button></header><p>为保护个人信息，请输入当前登录密码。内容只会复制到剪贴板，不会在页面显示。</p><label>当前密码<input v-model="reveal.password" type="password" autocomplete="current-password" required autofocus></label><button class="primary"><Check :size="18" aria-hidden="true"/>验证并复制</button></form></div>
  </div>
</template>

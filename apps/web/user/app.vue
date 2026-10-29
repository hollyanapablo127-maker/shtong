<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Check, Clipboard, KeyRound, LogOut, ShieldCheck, ShieldPlus, UserRound, X } from 'lucide-vue-next'
import { createSession, installSessionGuards, errorMessage } from '@shtong/web-shared'
import type { AdministrativeDivision, ProfileAddressInput, SensitiveProfileField } from '@shtong/api-sdk'
import SearchableSelect from './components/SearchableSelect.vue'

const runtimeConfig=useRuntimeConfig()
const session=createSession('WEB_USER',{baseUrl:String(runtimeConfig.public.apiBase||'/v1')})
const login=reactive({loginName:'',password:''}),passwords=reactive({current:'',next:'',confirm:''})
const active=ref<'home'|'profile'|'password'|'medical'>('home'),busy=ref(false),notice=ref(''),moduleError=ref('')
const moduleUrl=ref(''),moduleExitUrl=ref(''),moduleLoading=ref(false),moduleLeaving=ref(false),moduleFrame=ref<HTMLIFrameElement>()
const provinces=ref<AdministrativeDivision[]>([])
const completion=reactive({idCardNumber:'',mobileNumber:'',ethnicity:'',currentAddress:emptyAddress(),householdAddress:emptyAddress()})
const ethnicities=[
  '汉族','蒙古族','回族','藏族','维吾尔族','苗族','彝族','壮族','布依族','朝鲜族','满族','侗族','瑶族','白族','土家族','哈尼族','哈萨克族','傣族','黎族','傈僳族','佤族','畲族','高山族','拉祜族','水族','东乡族','纳西族','景颇族','柯尔克孜族','土族','达斡尔族','仫佬族','羌族','布朗族','撒拉族','毛难族','仡佬族','锡伯族','阿昌族','普米族','塔吉克族','怒族','乌孜别克族','俄罗斯族','鄂温克族','崩龙族','保安族','裕固族','京族','塔塔尔族','独龙族','鄂伦春族','赫哲族','门巴族','珞巴族','基诺族'
].map(name=>({code:name,name}))
const addressOptions=reactive({currentAddress:{cities:[] as AdministrativeDivision[],districts:[] as AdministrativeDivision[]},householdAddress:{cities:[] as AdministrativeDivision[],districts:[] as AdministrativeDivision[]}})
const addressKinds=['currentAddress','householdAddress'] as const
const reveal=reactive<{field:SensitiveProfileField|null;label:string;password:string}>({field:null,label:'',password:''})
const isStudent=computed(()=>session.state.profile?.roleCodes.includes('USER')===true)
const derivedAge=computed(()=>{
  const value=completion.idCardNumber.trim().toUpperCase()
  if(!/^\d{17}[0-9X]$/.test(value))return ''
  const birth=new Date(Number(value.slice(6,10)),Number(value.slice(10,12))-1,Number(value.slice(12,14)))
  if(Number.isNaN(birth.getTime()))return ''
  const today=new Date();let age=today.getFullYear()-birth.getFullYear()
  if(today.getMonth()<birth.getMonth()||(today.getMonth()===birth.getMonth()&&today.getDate()<birth.getDate()))age--
  return age>=0?String(age):''
})
const medicalInsuranceAvailable=computed(()=>session.state.bootstrap?.activeModules.some(module=>module.code==='medical-insurance')===true)
let moduleExitResolve: (()=>void)|undefined
let moduleExitTimer: number|undefined
let moduleTransitionId = 0
let removeSessionGuards:undefined|(()=>void)

function emptyAddress():ProfileAddressInput{return{provinceCode:'',cityCode:'',districtCode:'',detail:''}}
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
  moduleUrl.value='';moduleExitUrl.value='';moduleLoading.value=false;moduleLeaving.value=false;active.value='home'
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
onMounted(async()=>{window.addEventListener('message',onModuleMessage);removeSessionGuards=installSessionGuards(session.refresh);await session.restore()})
onBeforeUnmount(()=>{window.removeEventListener('message',onModuleMessage);removeSessionGuards?.()})
watch(()=>session.state.phase,phase=>{if(phase==='profile-completion')void prepareCompletion()},{immediate:true})
async function submitLogin(){busy.value=true;try{await session.login(login.loginName,login.password)}finally{busy.value=false}}
function validPassword(value:string){return value.length>=8&&value.length<=128&&/[A-Z]/.test(value)&&/[a-z]/.test(value)&&/\d/.test(value)}
async function submitPassword(){if(passwords.next!==passwords.confirm){notice.value='两次输入的新密码不一致';return}if(!validPassword(passwords.next)){notice.value='密码至少 8 位，并同时包含大写字母、小写字母和数字';return}busy.value=true;try{await session.changePassword(passwords.current,passwords.next);Object.assign(passwords,{current:'',next:'',confirm:''});notice.value='密码已更新';if(session.state.phase==='authenticated'){active.value='profile';await loadProfile()}}catch(error){notice.value=errorMessage(error)}finally{busy.value=false}}
async function loadProfile(){try{session.state.profile=await session.api.profile()}catch(error){notice.value=errorMessage(error)}}
async function prepareCompletion(){notice.value='';if(!session.state.profile)await loadProfile();if(!provinces.value.length)try{provinces.value=await session.api.divisions('PROVINCE')}catch(error){notice.value=errorMessage(error)}}
async function selectProvince(kind:'currentAddress'|'householdAddress'){
  const address=completion[kind];address.cityCode='';address.districtCode='';addressOptions[kind].districts=[]
  try{addressOptions[kind].cities=address.provinceCode?await session.api.divisions('CITY',address.provinceCode):[]}catch(error){addressOptions[kind].cities=[];notice.value=errorMessage(error)}
}
async function selectCity(kind:'currentAddress'|'householdAddress'){
  const address=completion[kind];address.districtCode='';try{addressOptions[kind].districts=address.cityCode?await session.api.divisions('DISTRICT',address.cityCode):[]}catch(error){addressOptions[kind].districts=[];notice.value=errorMessage(error)}
}
function validIdCard(value:string){const id=value.trim().toUpperCase();if(!/^\d{17}[0-9X]$/.test(id))return false;const weights=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2],checks='10X98765432';return checks[weights.reduce((sum,w,i)=>sum+Number(id[i])*w,0)%11]===id[17]}
async function submitCompletion(){
  notice.value=''
  if(!validIdCard(completion.idCardNumber)){notice.value='身份证号格式或校验位不正确';return}
  if(!completion.ethnicity){notice.value='请选择民族';return}
  if(!/^1[3-9]\d{9}$/.test(completion.mobileNumber)){notice.value='请输入正确的11位手机号';return}
  busy.value=true
  try{const result=await session.api.completeProfile(completion);session.state.profile=result.profile;await session.restore();notice.value='信息已完善';active.value='profile'}catch(error){notice.value=errorMessage(error)}finally{busy.value=false}
}
async function openProfile(){active.value='profile';notice.value='';await loadProfile()}
async function openMedicalInsurance(){
  moduleTransitionId++
  if(moduleExitTimer!==undefined){window.clearTimeout(moduleExitTimer);moduleExitTimer=undefined}
  if(moduleExitResolve){const resolve=moduleExitResolve;moduleExitResolve=undefined;resolve()}
  moduleError.value=''
  notice.value=''
  busy.value=true
  try{
    const ticket=await session.api.issueMedicalInsuranceTicket()
    const target=normalizeModuleUrl(ticket.redirectUrl)
    moduleUrl.value=target.toString()
    moduleExitUrl.value=new URL('/medical-insurance/exit/',target.origin).toString()
    moduleLoading.value=true
    moduleLeaving.value=false
    active.value='medical'
  }catch(error){moduleError.value=errorMessage(error)}finally{busy.value=false}
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
function openPassword(){active.value='password';notice.value='';Object.assign(passwords,{current:'',next:'',confirm:''})}
async function copyText(value:string,label:string){if(!value||value==='—')return;try{await navigator.clipboard.writeText(value);notice.value=`${label}已复制`}catch{notice.value='复制失败，请检查浏览器剪贴板权限'}}
function requestSensitive(field:SensitiveProfileField,label:string){reveal.field=field;reveal.label=label;reveal.password=''}
async function revealAndCopy(){if(!reveal.field||!reveal.password)return;busy.value=true;try{const result=await session.api.revealProfileField(reveal.field,reveal.password);await navigator.clipboard.writeText(result.value);notice.value=`${reveal.label}已验证并复制`;closeReveal()}catch(error){notice.value=errorMessage(error)}finally{reveal.password='';busy.value=false}}
function closeReveal(){reveal.field=null;reveal.label='';reveal.password=''}
function gender(value?:string|null){return value==='MALE'?'男':value==='FEMALE'?'女':value==='OTHER'?'其他':'未填写'}
function role(value:string){return ({USER:'学生',TEACHER_ADMIN:'教师/辅导员',STUDENT_ADMIN:'学生管理员',MEDICAL_INSURANCE_ADMIN:'医保系统管理员',SYSTEM_ADMIN:'系统管理员'} as Record<string,string>)[value]||value}
function orgType(value:string){return ({SCHOOL:'学校',COLLEGE:'学院',GRADE:'年级',CLASS:'班级'} as Record<string,string>)[value]||value}
</script>

<template>
  <main v-if="session.state.phase==='checking'" class="auth-gate" aria-live="polite"><div class="loader"/><p>正在检查登录状态</p></main>
  <main v-else-if="session.state.phase==='anonymous'" class="auth-layout">
    <section class="brand-panel"><div class="brand-mark">生</div><div><p class="eyebrow">学校账户</p><h1>生化通</h1><p>账户登录</p></div><div class="security-note"><ShieldCheck :size="22" aria-hidden="true"/><span>保护个人账户与资料</span></div></section>
    <form class="auth-form" @submit.prevent="submitLogin"><div><p class="eyebrow">账户登录</p><h2>进入工作台</h2></div><label>登录账号<input v-model="login.loginName" autocomplete="username" required></label><label>密码<input v-model="login.password" type="password" autocomplete="current-password" required></label><p v-if="session.state.error" role="alert" class="error">{{session.state.error}}</p><button class="primary" :disabled="busy">{{busy?'正在登录':'登录'}}</button></form>
  </main>
  <main v-else-if="session.state.phase==='password'" class="centered"><form class="dialog" @submit.prevent="submitPassword"><KeyRound :size="28" aria-hidden="true"/><h1>首次登录需要修改密码</h1><p>新密码至少 8 位，并同时包含大写字母、小写字母和数字。</p><label>临时密码<input v-model="passwords.current" type="password" autocomplete="current-password" maxlength="128" required></label><label>新密码<input v-model="passwords.next" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><label>确认新密码<input v-model="passwords.confirm" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><p v-if="notice" role="alert" class="error">{{notice}}</p><button class="primary" :disabled="busy">更新密码并继续</button><button type="button" class="text" @click="session.logout">退出</button></form></main>
  <main v-else-if="session.state.phase==='profile-completion'" class="completion-shell">
    <header class="completion-header"><div class="brand-button"><span class="brand-mark small">生</span><strong>生化通</strong></div><button class="text" @click="session.logout"><LogOut :size="18" aria-hidden="true"/>退出登录</button></header>
    <form class="completion-form" @submit.prevent="submitCompletion">
      <div><p class="eyebrow">首次登录</p><h1>完善个人信息</h1><p class="form-intro">以下信息用于身份核对和学校联系，全部为必填项，完成前不能进入工作台。</p></div>
      <section><h2>账户基本资料</h2><dl class="readonly-summary"><div><dt>姓名</dt><dd>{{session.state.profile?.displayName||'—'}}</dd></div><div><dt>学号</dt><dd>{{session.state.profile?.personNo||'—'}}</dd></div><div><dt>学校院系班级</dt><dd>{{session.state.profile?.organizationPath.map(item=>item.name).join(' / ')||'—'}}</dd></div></dl></section>
      <section class="form-grid"><h2>身份与联系方式</h2><label>身份证号 <span aria-hidden="true">*</span><input v-model.trim="completion.idCardNumber" inputmode="text" autocomplete="off" minlength="18" maxlength="18" pattern="[0-9]{17}[0-9Xx]" required><small>请按居民身份证填写18位号码，年龄将自动计算。</small></label><label>年龄<input :value="derivedAge || '输入身份证号后自动计算'" readonly aria-readonly="true"></label><label>民族 <span aria-hidden="true">*</span><SearchableSelect v-model="completion.ethnicity" :options="ethnicities" aria-label="民族" placeholder="搜索或选择民族"/><small>民族由本人在此首次完善；医保模块只读同步。</small></label><label>手机号 <span aria-hidden="true">*</span><input v-model.trim="completion.mobileNumber" type="tel" inputmode="numeric" autocomplete="tel" maxlength="11" pattern="1[3-9][0-9]{9}" required></label></section>
      <section v-for="kind in addressKinds" :key="kind" class="address-section"><h2>{{kind==='currentAddress'?'现家庭住址':'户籍地址'}}</h2><p v-if="kind==='householdAddress'" class="field-help">请按户口簿或身份证登记地址填写。</p><div class="address-grid"><label>省/自治区/直辖市 <span aria-hidden="true">*</span><SearchableSelect v-model="completion[kind].provinceCode" :options="provinces" aria-label="省自治区直辖市" placeholder="搜索省市" @change="selectProvince(kind)"/></label><label>市 <span aria-hidden="true">*</span><SearchableSelect v-model="completion[kind].cityCode" :options="addressOptions[kind].cities" :disabled="!completion[kind].provinceCode" aria-label="市" placeholder="搜索城市" @change="selectCity(kind)"/></label><label>区/县 <span aria-hidden="true">*</span><SearchableSelect v-model="completion[kind].districtCode" :options="addressOptions[kind].districts" :disabled="!completion[kind].cityCode" aria-label="区县" placeholder="搜索区县"/></label><label class="detail-address">详细地址 <span aria-hidden="true">*</span><textarea v-model.trim="completion[kind].detail" rows="3" minlength="2" maxlength="200" placeholder="街道、门牌号、小区、楼栋和房间号" required/></label></div></section>
      <p v-if="notice" role="alert" class="error">{{notice}}</p><button class="primary submit-completion" :disabled="busy">{{busy?'正在保存':'保存并进入工作台'}}</button>
    </form>
  </main>
  <div v-else class="app-shell">
    <header><button class="brand-button" @click="active='home'" aria-label="返回工作台"><span class="brand-mark small">生</span><strong>生化通</strong></button><nav aria-label="账户操作"><button :class="{active:active==='profile'||active==='password'}" @click="openProfile"><UserRound :size="18" aria-hidden="true"/>个人中心</button><button @click="session.logout"><LogOut :size="18" aria-hidden="true"/>退出</button></nav></header>
    <main class="workspace">
      <section v-if="active==='home'" class="empty-workspace"><p class="eyebrow">我的工作台</p><h1>已授权模块</h1><div v-if="medicalInsuranceAvailable" class="module-grid"><button class="module-entry" :disabled="busy" @click="openMedicalInsurance"><span class="module-icon"><ShieldPlus :size="25" aria-hidden="true"/></span><span><strong>医保信息系统</strong><small>学生信息与管辖范围在同一模块中呈现</small></span><ArrowRight :size="18" aria-hidden="true"/></button><p v-if="moduleError" class="error" role="alert">{{moduleError}}</p></div><p v-else class="empty-copy">当前没有已开放的业务模块</p><p v-if="notice" role="status" class="notice">{{notice}}</p></section>
      <section v-else-if="active==='medical'" class="embedded-module"><div class="embedded-module-bar"><div><p class="eyebrow">工作台模块</p><h1>医保信息系统</h1></div><button class="secondary" :disabled="moduleLeaving" @click="closeMedicalInsurance"><ArrowLeft :size="18" aria-hidden="true"/>{{moduleLeaving?'正在返回':'返回工作台'}}</button></div><div class="embedded-module-frame"><div v-if="moduleLoading" class="embedded-module-loading" role="status">{{moduleLeaving?'正在安全退出医保模块':'正在打开医保信息系统'}}</div><iframe ref="moduleFrame" :key="moduleUrl" :src="moduleUrl" title="医保信息系统" @load="onModuleLoad"/></div></section>
      <section v-else-if="active==='profile'" class="profile"><button class="back-button" @click="active='home'"><ArrowLeft :size="18" aria-hidden="true"/>返回工作台</button><div><p class="eyebrow">个人中心</p><h1>账户资料</h1><p class="copy-hint"><Clipboard :size="16" aria-hidden="true"/>点击任一资料即可复制；敏感资料需先验证密码。</p></div>
        <dl class="profile-list">
          <div><dt>姓名</dt><dd><button class="copy-value" @click="copyText(session.state.profile?.displayName||'', '姓名')">{{session.state.profile?.displayName||'—'}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div>
          <div><dt>学号/工号</dt><dd><button class="copy-value" @click="copyText(session.state.profile?.personNo||'', '学号/工号')">{{session.state.profile?.personNo||'—'}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div>
          <div><dt>身份</dt><dd><button class="copy-value" @click="copyText(session.state.profile?.roleCodes.map(role).join('、')||'', '身份')">{{session.state.profile?.roleCodes.map(role).join('、')||'—'}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div>
          <div v-if="isStudent"><dt>性别</dt><dd><button class="copy-value" @click="copyText(gender(session.state.profile?.gender),'性别')">{{gender(session.state.profile?.gender)}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div><div v-if="isStudent"><dt>民族</dt><dd><button class="copy-value" @click="copyText(session.state.profile?.ethnicity||'未填写','民族')">{{session.state.profile?.ethnicity||'未填写'}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div><div v-if="isStudent"><dt>年龄</dt><dd>{{session.state.profile?.age??'—'}}</dd></div>
          <div v-for="item in session.state.profile?.organizationPath" :key="item.type"><dt>{{orgType(item.type)}}</dt><dd><button class="copy-value" @click="copyText(item.name,orgType(item.type))">{{item.name}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div>
          <div v-if="isStudent"><dt>对应辅导员</dt><dd><button class="copy-value" @click="copyText(session.state.profile?.counselors.map(item=>`${item.displayName}（${item.personNo}）`).join('、')||'暂未匹配','对应辅导员')">{{session.state.profile?.counselors.map(item=>`${item.displayName}（${item.personNo}）`).join('、')||'暂未匹配'}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div>
          <template v-for="assignment in session.state.profile?.managedScopes" :key="assignment.roleCode"><div><dt>{{role(assignment.roleCode)}}管辖范围</dt><dd><button class="copy-value" @click="copyText(assignment.scopes.map(item=>item.path).join('、')||'—','管辖范围')">{{assignment.scopes.map(item=>item.path).join('、')||'—'}}<Clipboard :size="16" aria-hidden="true"/></button></dd></div></template>
          <template v-if="isStudent"><div><dt>身份证号</dt><dd><button class="copy-value sensitive" @click="requestSensitive('ID_CARD_NUMBER','身份证号')">{{session.state.profile?.idCardNumberMasked||'—'}}<ShieldCheck :size="16" aria-hidden="true"/></button></dd></div><div><dt>手机号</dt><dd><button class="copy-value sensitive" @click="requestSensitive('MOBILE_NUMBER','手机号')">{{session.state.profile?.mobileNumberMasked||'—'}}<ShieldCheck :size="16" aria-hidden="true"/></button></dd></div><div><dt>现家庭住址</dt><dd><button class="copy-value sensitive" @click="requestSensitive('CURRENT_ADDRESS','现家庭住址')">{{session.state.profile?.currentAddressMasked||'—'}}<ShieldCheck :size="16" aria-hidden="true"/></button></dd></div><div><dt>户籍地址</dt><dd><button class="copy-value sensitive" @click="requestSensitive('HOUSEHOLD_ADDRESS','户籍地址')">{{session.state.profile?.householdAddressMasked||'—'}}<ShieldCheck :size="16" aria-hidden="true"/></button></dd></div></template>
        </dl><button class="secondary profile-action" @click="openPassword"><KeyRound :size="18" aria-hidden="true"/>修改密码</button><p v-if="notice" role="status" class="notice">{{notice}}</p></section>
      <section v-else class="profile password-page"><button class="back-button" @click="active='profile'"><ArrowLeft :size="18" aria-hidden="true"/>返回个人中心</button><div><p class="eyebrow">账户安全</p><h1>修改密码</h1></div><form @submit.prevent="submitPassword"><label>当前密码<input v-model="passwords.current" type="password" autocomplete="current-password" maxlength="128" required></label><label>新密码<input v-model="passwords.next" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><label>确认新密码<input v-model="passwords.confirm" type="password" autocomplete="new-password" minlength="8" maxlength="128" required></label><button class="primary" :disabled="busy">{{busy?'正在修改':'确认修改'}}</button></form><p v-if="notice" role="status" class="notice">{{notice}}</p></section>
    </main>
    <div v-if="reveal.field" class="modal-layer" @click.self="closeReveal"><form class="reveal-dialog" role="dialog" aria-modal="true" aria-labelledby="reveal-title" @submit.prevent="revealAndCopy"><button type="button" class="icon-button" aria-label="关闭" @click="closeReveal"><X :size="20" aria-hidden="true"/></button><ShieldCheck :size="28" aria-hidden="true"/><h2 id="reveal-title">验证后复制{{reveal.label}}</h2><p>为保护个人信息，请输入当前登录密码。验证成功后内容会直接复制，不会在页面显示。</p><label>当前密码<input v-model="reveal.password" type="password" autocomplete="current-password" required autofocus></label><button class="primary" :disabled="busy"><Check :size="18" aria-hidden="true"/>验证并复制</button></form></div>
  </div>
</template>

import {computed,reactive} from 'vue'
import {errorMessage} from './presentation'
import {ShtongApi,type Bootstrap,type Channel,type Profile} from '@shtong/api-sdk'
export * from './presentation'

export interface SessionOptions {baseUrl?:string}

export function createSession(channel:Channel,options:SessionOptions={}){
  const environment=(import.meta as ImportMeta&{env?:Record<string,string>}).env
  const state=reactive<{phase:'checking'|'anonymous'|'password'|'profile-completion'|'authenticated';bootstrap?:Bootstrap;profile?:Profile;error?:string}>({phase:'checking'})
  function clear(){state.bootstrap=undefined;state.profile=undefined;state.phase='anonymous'}
  const api=new ShtongApi(options.baseUrl||environment?.VITE_API_BASE||'/v1',channel,undefined,clear)
  const isSystemAdmin=computed(()=>state.bootstrap?.activeContext?.workspace==='SYSTEM_ADMIN')
  async function restore(silent=false){
    const keepAuthenticated=silent&&state.phase==='authenticated'&&state.bootstrap!==undefined
    if(!keepAuthenticated)state.phase='checking'
    state.error=undefined
    try{
      state.bootstrap=await api.bootstrap()
      state.phase=state.bootstrap.profileCompletionRequired?'profile-completion':'authenticated'
    }catch(error){
      // The API client clears a genuinely invalid session through its 401
      // callback. A transient background refresh failure must not unmount an
      // already authenticated module and consume its one-time entry URL.
      const code=(error as {code?:string})?.code
      if(!keepAuthenticated||state.phase==='anonymous'||code==='CONTRACT_VERSION_MISMATCH')clear()
    }
  }
  const refresh=()=>restore(true)
  async function login(loginName:string,password:string){state.error=undefined;try{const result=await api.login(loginName,password);if(result.status==='PASSWORD_CHANGE_REQUIRED'){state.phase='password'}else if(result.status==='PROFILE_COMPLETION_REQUIRED'){state.phase='profile-completion';state.profile=await api.profile()}else if(result.status==='AUTHENTICATED'){await restore()}else{clear();state.error=errorMessage({code:'INVALID_CREDENTIALS'})}}catch(error){clear();state.error=errorMessage(error)}}
  async function switchWorkspace(workspace:'ADMIN'|'SYSTEM_ADMIN'){state.error=undefined;try{await api.switchContext(workspace);await restore()}catch(error){state.error=errorMessage(error)}}
  async function changePassword(currentPassword:string,newPassword:string){state.error=undefined;try{await api.changePassword(currentPassword,newPassword);await restore()}catch(error){state.error=errorMessage(error);throw error}}
  async function logout(){try{await api.logout()}finally{clear();history.replaceState({shtongSession:'signed-out'},'',location.pathname);history.pushState({shtongSession:'signed-out-guard'},'',location.pathname)}}
  return{api,state,isSystemAdmin,restore,refresh,login,switchWorkspace,changePassword,logout,clear}
}

export function installSessionGuards(restore:()=>Promise<void>){const pageshow=(event:PageTransitionEvent)=>{if(event.persisted)void restore()};const visibility=()=>{if(document.visibilityState==='visible')void restore()};addEventListener('pageshow',pageshow);document.addEventListener('visibilitychange',visibility);return()=>{removeEventListener('pageshow',pageshow);document.removeEventListener('visibilitychange',visibility)}}

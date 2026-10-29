// Typed projection of packages/contracts/openapi/account-access-baseline.v1.json.
export type Channel = 'WEB_USER' | 'WEB_ADMIN' | 'ANDROID' | 'IOS'
export type Workspace = 'USER' | 'ADMIN' | 'SYSTEM_ADMIN'
export type RoleCode = 'USER' | 'TEACHER_ADMIN' | 'STUDENT_ADMIN' | 'MEDICAL_INSURANCE_ADMIN' | 'SYSTEM_ADMIN'
export type Gender = 'MALE' | 'FEMALE' | 'OTHER'
export type LifecycleStatus = 'ACTIVE' | 'GRADUATED' | 'DELETED'
export type OrgUnitType = 'SCHOOL' | 'COLLEGE' | 'GRADE' | 'CLASS'
export interface ApiError { code:string;messageKey:string;requestId:string;fieldErrors?:Array<{field:string;code:string}>;status?:number }
export interface LoginResponse { status:'AUTHENTICATED'|'PASSWORD_CHANGE_REQUIRED'|'PROFILE_COMPLETION_REQUIRED'|'DENIED';challengeId?:string|null;expiresAt?:string|null;allowedActions?:string[];session?:SessionEstablished|null }
export interface SessionEstablished { sessionId:string;context:{id:string;workspace:Workspace;assuranceLevel:string;expiresAt:string};tokenDelivery:'HTTP_ONLY_COOKIE'|'RESPONSE_BODY';mobileTokens?:{tokenType:'Bearer';accessToken:string;refreshToken:string;accessTokenExpiresAt:string;refreshTokenExpiresAt:string}|null }
export interface Bootstrap { contractVersion:string;authorizationVersion:number;profileCompletionRequired:boolean;activeContext?:SessionEstablished['context'];activeModules:Array<{code:string;routeKey:string;order:number;externalSystem?:string;sso?:string}>;shellActions:string[];availableWorkspaces:Array<{code:Workspace;requiresStepUp:boolean}> }
export interface ProfileOrganizationItem {type:OrgUnitType;code:string;name:string}
export interface ProfileCounselor {displayName:string;personNo:string}
export interface ProfileScope {type:'SYSTEM'|'COLLEGE'|'GRADE'|'CLASS';name:string;path:string}
export interface ProfileManagedScope {roleCode:RoleCode;scopes:ProfileScope[]}
export interface Profile {accountId:string;displayName:string;personNo:string;gender?:Gender|null;ethnicity?:string|null;age?:number|null;roleCodes:RoleCode[];organizationPath:ProfileOrganizationItem[];counselors:ProfileCounselor[];managedScopes:ProfileManagedScope[];profileCompletionRequired:boolean;idCardNumberMasked?:string|null;mobileNumberMasked?:string|null;currentAddressMasked?:string|null;householdAddressMasked?:string|null;version:number}
export interface AdministrativeDivision {code:string;name:string}
export interface ProfileAddressInput {provinceCode:string;cityCode:string;districtCode:string;detail:string}
export interface ProfileAddressDetail extends ProfileAddressInput {provinceName:string;cityName:string;districtName:string}
export interface ProfileCompletionInput {idCardNumber:string;mobileNumber:string;ethnicity:string;currentAddress:ProfileAddressInput;householdAddress:ProfileAddressInput}
export interface ProfileCompletionResponse {profile:Profile;session:SessionEstablished}
export type SensitiveProfileField='ID_CARD_NUMBER'|'MOBILE_NUMBER'|'CURRENT_ADDRESS'|'HOUSEHOLD_ADDRESS'
export interface AccountSummary {graduatedAt?:string|null;graduatedReason?:string|null;id:string;loginName:string;displayName:string;personNo:string;gender?:Gender|null;ethnicity?:string|null;age?:number|null;lifecycleStatus:LifecycleStatus;securityStatus:string;roleCodes:RoleCode[];passwordChangeRequired:boolean;version:number}
export interface AccountDetail extends AccountSummary {classId?:string|null;organizationPath:ProfileOrganizationItem[];idCardNumber?:string|null;mobileNumber?:string|null;currentAddress?:ProfileAddressDetail|null;householdAddress?:ProfileAddressDetail|null;profileCompleted:boolean;lastLoginAt?:string|null;createdAt:string;updatedAt:string}
export interface Page<T> {items:T[];nextCursor?:string|null;hasMore:boolean}
export type AccountPage = Page<AccountSummary>
export interface OrgUnit {id:string;type:OrgUnitType;parentId?:string|null;code:string;name:string;status:'ACTIVE'|'DELETED';version:number}
export interface RoleAssignment {id:string;roleCode:RoleCode;status:string;scopeCeilings:OrgUnit[];validFrom:string;validTo?:string|null;version:number}
export interface AuditEvent {eventId:string;eventCode:string;occurredAt:string;actorAccountId?:string|null;authorizationDecision:string;outcome:string;requestId:string;subjectCount:number}
export interface AuditEventDetail extends AuditEvent {workspace?:Workspace;channel?:string;reasonCode?:string|null;subjects:string[];resources:Record<string,unknown>[];changes:Record<string,unknown>[];authorizationEvidence:Record<string,unknown>[]}
export interface JobAccepted {jobId:string;state:string;controlRequestId?:string|null}
export interface JobStatus {id:string;state:'PENDING'|'RUNNING'|'READY'|'FAILED'|'EXPIRED'|'COMPLETED';resultCount?:number|null;failureCode?:string|null;createdAt:string;completedAt?:string|null}
export interface DownloadTicket {url:string;expiresAt:string;singleUse:boolean}
export interface ImportJob {id:string;state:'UPLOADED'|'VALIDATING'|'VALID'|'INVALID'|'COMMITTED'|'EXPIRED'|'FAILED';templateVersion:string;rowCount:number;errorCount:number;organizationRevision:number}
export interface ImportIssue {sheet:string;row:number;field:string;code:string;messageKey:string}
export interface ControlRequest {id:string;commandType:string;state:string;requestedBy:string;requestedAt:string;expiresAt:string;decidedBy?:string|null;decidedAt?:string|null;targetCount:number;reason:string;version:number}
export interface MedicalInsuranceTicket {moduleCode:'medical-insurance';audience:'medical-insurance';redirectUrl:string;expiresAt:string;singleUse:true}

interface RequestContext {requestId:string;idempotencyKey?:string}
const CONTRACT_VERSION='account-access-baseline.v1'

export class ShtongApi {
  private refreshPromise?:Promise<void>
  constructor(public readonly baseUrl='/v1',private readonly channel:Channel='WEB_ADMIN',private token?:string,private readonly onSessionInvalidated:()=>void=()=>{}){}
  setToken(value?:string){this.token=value}
  async request<T>(path:string,init:RequestInit={}):Promise<T>{const method=(init.method||'GET').toUpperCase();const context:RequestContext={requestId:crypto.randomUUID(),idempotencyKey:method==='GET'||method==='HEAD'?undefined:crypto.randomUUID()};return this.execute<T>(path,init,context,true)}
  private async execute<T>(path:string,init:RequestInit,context:RequestContext,canRefresh:boolean):Promise<T>{
    const method=(init.method||'GET').toUpperCase();const headers=new Headers(init.headers)
    if(init.body!==undefined&&!(init.body instanceof FormData))headers.set('Content-Type','application/json')
    headers.set('X-Channel',this.channel);headers.set('X-Request-Id',context.requestId);headers.set('Cache-Control','no-store')
    if(context.idempotencyKey)headers.set('Idempotency-Key',context.idempotencyKey)
    const csrf=this.cookie('SHTONG_CSRF');if(!['GET','HEAD','OPTIONS'].includes(method)&&csrf)headers.set('X-CSRF-Token',csrf)
    const url=this.resolve(path);const apiOrigin=url.origin===this.apiOrigin()
    if(this.token&&apiOrigin)headers.set('Authorization',`Bearer ${this.token}`)
    const response=await fetch(url,{...init,method,headers,credentials:apiOrigin?'include':'omit',cache:'no-store'})
    if(!response.ok){const body=await response.json().catch(()=>({code:'NETWORK_ERROR',messageKey:'error.network',requestId:context.requestId})) as ApiError;body.status=response.status;if(apiOrigin&&canRefresh&&!path.includes('/auth/token/refresh')&&!path.includes('/auth/login')&&!path.includes('/auth/mfa')&&(response.status===401||body.code==='AUTHORIZATION_VERSION_STALE')){try{await this.refresh();return await this.execute<T>(path,init,context,false)}catch{this.onSessionInvalidated();throw body}}if(apiOrigin&&(response.status===401||body.code==='AUTHORIZATION_VERSION_STALE'))this.onSessionInvalidated();throw body}
    if(response.status===204)return undefined as T
    const type=response.headers.get('content-type')||'';return(type.includes('json')?await response.json():await response.blob()) as T
  }
  private refresh(){if(!this.refreshPromise)this.refreshPromise=this.execute<SessionEstablished>('/auth/token/refresh',{method:'POST',body:JSON.stringify({channel:this.channel,refreshToken:null})},{requestId:crypto.randomUUID(),idempotencyKey:crypto.randomUUID()},false).then(result=>{const tokens=result.mobileTokens;if(tokens)this.token=tokens.accessToken}).finally(()=>{this.refreshPromise=undefined});return this.refreshPromise}
  private base(){return new URL(`${this.baseUrl.replace(/\/$/,'')}/`,location.origin)}
  private apiOrigin(){return this.base().origin}
  private resolve(path:string){if(/^https?:\/\//.test(path))return new URL(path);if(path.startsWith('/v1/'))return new URL(path,this.apiOrigin());return new URL(path.replace(/^\//,''),this.base())}
  private cookie(name:string){if(typeof document==='undefined')return undefined;return document.cookie.split(';').map(value=>value.trim()).find(value=>value.startsWith(`${name}=`))?.slice(name.length+1)}
  login(loginName:string,password:string){return this.request<LoginResponse>('/auth/login',{method:'POST',body:JSON.stringify({loginName,password,channel:this.channel})})}
  switchContext(workspace:Workspace){return this.request<SessionEstablished>('/session/context',{method:'POST',body:JSON.stringify({workspace})})}
  async bootstrap(){const value=await this.request<Bootstrap>('/session/bootstrap');if(value.contractVersion!==CONTRACT_VERSION)throw ({code:'CONTRACT_VERSION_MISMATCH',messageKey:'error.contract.version',requestId:''} satisfies ApiError);return value}
  logout(){return this.request<void>('/auth/logout',{method:'POST'})}
  changePassword(currentPassword:string,newPassword:string){return this.request<SessionEstablished>('/auth/password/change',{method:'POST',body:JSON.stringify({currentPassword,newPassword})})}
  profile(){return this.request<Profile>('/profile')}
  divisions(level:'PROVINCE'|'CITY'|'DISTRICT',parentCode?:string){const query=new URLSearchParams({level});if(parentCode)query.set('parentCode',parentCode);return this.request<AdministrativeDivision[]>(`/reference/administrative-divisions?${query}`)}
  completeProfile(value:ProfileCompletionInput){return this.request<ProfileCompletionResponse>('/profile/completion',{method:'POST',body:JSON.stringify(value)})}
  revealProfileField(field:SensitiveProfileField,password:string){return this.request<{field:SensitiveProfileField;value:string}>(`/profile/sensitive-fields/${field}/reveal`,{method:'POST',body:JSON.stringify({password})})}
  issueMedicalInsuranceTicket(){return this.request<MedicalInsuranceTicket>('/modules/medical-insurance/sso/ticket',{method:'POST'})}
  download(ticket:DownloadTicket){return this.request<Blob>(ticket.url)}
}

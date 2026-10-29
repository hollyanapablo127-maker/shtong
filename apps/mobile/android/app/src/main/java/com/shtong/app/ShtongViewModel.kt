package com.shtong.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.time.Instant

enum class Phase { CHECKING, LOGIN, PASSWORD, PROFILE_COMPLETION, APP }

/** Same rule as the server: 8 to 128 characters with an ASCII uppercase letter, lowercase letter and digit. */
internal fun meetsPasswordPolicy(value:String)=value.length in 8..128&&value.any{it in 'A'..'Z'}&&value.any{it in 'a'..'z'}&&value.any{it in '0'..'9'}
enum class Route { HOME, ACCOUNTS, GRADUATES, AUTHORIZATION, ORGANIZATION, AUDIT, MEDICAL_INSURANCE, PROFILE, PASSWORD }
data class UiState(
    val phase:Phase=Phase.CHECKING,val route:Route=Route.HOME,val busy:Boolean=false,val error:String?=null,val notice:String?=null,
    val bootstrap:Bootstrap?=null,val profile:Profile?=null,val accounts:List<AccountSummary> = emptyList(),val nextCursor:String?=null,
    val selected:Set<String> = emptySet(),val orgUnits:List<OrgUnit> = emptyList(),val assignments:List<RoleAssignment> = emptyList(),val audits:List<AuditEvent> = emptyList(),val controls:List<JsonObject> = emptyList(),
    val exportBytes:ByteArray?=null,val exportFileName:String="shtong-export.csv",val exportMime:String="text/csv",val importJobId:String?=null,val importRevision:Long=0,val importRowCount:Int=0,val importState:String?=null,val importErrors:List<ImportError> = emptyList(),val medicalInsuranceUrl:String?=null,
    val accountOptions:List<AccountSummary> = emptyList(),val accountOptionsNext:String?=null,val accountDetail:AccountDetail?=null,val auditDetail:AuditEventDetail?=null,val pendingCredentialCommandId:String?=null,val auditExportJobId:String?=null,val newAccountPassword:String?=null,
    val provinces:List<AdministrativeDivision> = emptyList(),val currentCities:List<AdministrativeDivision> = emptyList(),val currentDistricts:List<AdministrativeDivision> = emptyList(),val householdCities:List<AdministrativeDivision> = emptyList(),val householdDistricts:List<AdministrativeDivision> = emptyList()
)

class ShtongViewModel(application:Application):AndroidViewModel(application) {
    private val store=SecureStore(application)
    private val api=ApiClient(store)
    private var pendingDeepLink:Route?=null
    private var currentQuery=""
    private var currentLifecycle="ACTIVE"
    private var currentOrg=""
    private var currentRole=""
    private var importCacheFile:File?=null
    var state by mutableStateOf(UiState()); private set

    init { restore() }
    private fun launch(block:suspend()->Unit)=viewModelScope.launch{
        if(state.busy)return@launch
        state=state.copy(busy=true,error=null,notice=null)
        try{block()}
        catch(_:SessionInvalidException){invalidateSession()}
        catch(e:ApiException){if(e.status==401||e.failure.code=="AUTHORIZATION_VERSION_STALE")invalidateSession() else state=state.copy(error=errorText(e.failure.code))}
        catch(_:Exception){state=state.copy(error="网络连接失败，请稍后重试")}
        finally{state=state.copy(busy=false)}
    }
    private fun invalidateSession(){store.clear();deleteImportCache();state=UiState(phase=Phase.LOGIN,error="会话已失效，请重新登录")}
    fun restore(){if(store.access()==null){state=UiState(phase=Phase.LOGIN);return};launch{bootstrap()}}
    fun handleDeepLink(uri:Uri?){val route=uri?.lastPathSegment?.uppercase()?.let{runCatching{Route.valueOf(it)}.getOrNull()}?:return;if(state.phase!=Phase.APP){pendingDeepLink=route;restore()}else if(allowedRoutes().contains(route))navigate(route)}
    fun login(name:String,password:String){
        val loginName=name.trim()
        if(loginName.isBlank()||password.isBlank()){
            state=state.copy(error="请输入登录账号和密码")
            return
        }
        launch{val result:LoginResponse=api.post("/auth/login",LoginRequest(loginName,password,ANDROID_CHANNEL));when(result.status){"PASSWORD_CHANGE_REQUIRED"->{result.session?.mobileTokens?.let(::saveTokens);state=state.copy(phase=Phase.PASSWORD)};"PROFILE_COMPLETION_REQUIRED","AUTHENTICATED"->{result.session?.mobileTokens?.let(::saveTokens);bootstrap()};else->state=state.copy(phase=Phase.LOGIN,error="账号或密码不正确，请重新输入")}}
    }
    fun switchWorkspace(workspace:String)=launch{api.switchContext(workspace);bootstrap()}
    fun changePassword(current:String,next:String)=launch{val result:SessionEstablished=api.post("/auth/password/change",buildJsonObject{put("currentPassword",current);put("newPassword",next)});result.mobileTokens?.let(::saveTokens);bootstrap()}
    fun changeProfilePassword(current:String,next:String)=launch{val result:SessionEstablished=api.post("/auth/password/change",buildJsonObject{put("currentPassword",current);put("newPassword",next)});result.mobileTokens?.let(::saveTokens);val boot:Bootstrap=api.get("/session/bootstrap");state=state.copy(phase=Phase.APP,bootstrap=boot,route=Route.PROFILE,profile=api.get("/profile"),notice="密码已修改，其他旧会话已退出")}
    private suspend fun bootstrap(){val boot:Bootstrap=api.get("/session/bootstrap");if(boot.contractVersion!=Contract.VERSION)throw ApiException(ApiFailure("CONTRACT_VERSION_MISMATCH"),409);if(boot.profileCompletionRequired){state=state.copy(phase=Phase.PROFILE_COMPLETION,bootstrap=boot,profile=api.get("/profile"));loadCompletionOptionsInline();return};val initial=Route.HOME;state=state.copy(phase=Phase.APP,bootstrap=boot,route=pendingDeepLink?.takeIf{routeAllowed(it,boot)}?:initial);pendingDeepLink=null;loadForRouteInline(state.route)}
    fun logout()=viewModelScope.launch{runCatching{api.request<Unit>("/auth/logout","POST")};store.clear();deleteImportCache();state=UiState(phase=Phase.LOGIN)}
    fun openMedicalInsurance(onOpen:(String)->Unit={})=launch{val url=api.issueMedicalInsuranceTicket().redirectUrl;state=state.copy(medicalInsuranceUrl=url,route=Route.MEDICAL_INSURANCE);onOpen(url)}
    fun closeMedicalInsurance(){state=state.copy(medicalInsuranceUrl=null,route=Route.HOME,error=null,notice=null)}
    fun navigate(route:Route){if(state.busy||!allowedRoutes().contains(route))return;if(route in setOf(Route.ACCOUNTS,Route.GRADUATES)){currentQuery="";currentRole="";currentOrg="";currentLifecycle=if(route==Route.GRADUATES)"GRADUATED" else "ACTIVE";state=state.copy(accounts=emptyList(),nextCursor=null,selected=emptySet())};state=state.copy(route=route,error=null,notice=null);when(route){Route.ACCOUNTS,Route.GRADUATES->loadAccounts();Route.ORGANIZATION->loadOrganizations();Route.AUDIT->loadAudit();Route.PROFILE->loadProfile();else->{}}}
    private fun allowedRoutes():Set<Route>{val boot=state.bootstrap?:return emptySet();val medical=boot.activeModules.any{it.code=="medical-insurance"};if(boot.activeContext?.workspace!="SYSTEM_ADMIN")return (setOf(Route.HOME,Route.PROFILE,Route.PASSWORD)+if(medical)setOf(Route.MEDICAL_INSURANCE)else emptySet());val active=activeSystemRoutes(boot);return (active+listOf(Route.HOME,Route.PROFILE,Route.PASSWORD)+if(medical)listOf(Route.MEDICAL_INSURANCE)else emptyList()).toSet()}
    private fun routeAllowed(route:Route,boot:Bootstrap):Boolean{val medical=boot.activeModules.any{it.code=="medical-insurance"};return if(boot.activeContext?.workspace=="SYSTEM_ADMIN"){route==Route.HOME||route==Route.PROFILE||route==Route.PASSWORD||route in activeSystemRoutes(boot)||(medical&&route==Route.MEDICAL_INSURANCE)}else route==Route.HOME||route==Route.PROFILE||route==Route.PASSWORD||(medical&&route==Route.MEDICAL_INSURANCE)}
    private fun activeSystemRoutes(boot:Bootstrap)=boot.activeModules.sortedBy{it.order}.mapNotNull{when(it.code){"system.accounts"->Route.ACCOUNTS;"system.graduates"->Route.GRADUATES;"system.authorization"->Route.AUTHORIZATION;"system.organization"->Route.ORGANIZATION;"system.audit"->Route.AUDIT;else->null}}
    private suspend fun loadForRouteInline(route:Route){when(route){Route.ACCOUNTS,Route.GRADUATES->{currentLifecycle=if(route==Route.GRADUATES)"GRADUATED" else "ACTIVE";loadAccountsInline()};Route.ORGANIZATION->loadOrganizationsInline();Route.AUDIT->loadAuditInline();Route.PROFILE->state=state.copy(profile=api.get("/profile"));else->{}}}

    fun loadProfile()=launch{state=state.copy(profile=api.get("/profile"))}
    fun loadCompletionOptions()=launch{loadCompletionOptionsInline()}
    private suspend fun loadCompletionOptionsInline(){if(state.profile==null)state=state.copy(profile=api.get("/profile"));if(state.provinces.isEmpty())state=state.copy(provinces=api.get("/reference/administrative-divisions?level=PROVINCE"))}
    fun selectProfileProvince(kind:String,provinceCode:String)=launch{val values:List<AdministrativeDivision> = if(provinceCode.isBlank())emptyList()else api.get("/reference/administrative-divisions?level=CITY&parentCode=${enc(provinceCode)}");state=if(kind=="current")state.copy(currentCities=values,currentDistricts=emptyList())else state.copy(householdCities=values,householdDistricts=emptyList())}
    fun selectProfileCity(kind:String,cityCode:String)=launch{val values:List<AdministrativeDivision> = if(cityCode.isBlank())emptyList()else api.get("/reference/administrative-divisions?level=DISTRICT&parentCode=${enc(cityCode)}");state=if(kind=="current")state.copy(currentDistricts=values)else state.copy(householdDistricts=values)}
    fun completeProfile(idCardNumber:String,mobileNumber:String,ethnicity:String,current:ProfileAddressInput,household:ProfileAddressInput)=launch{val response:ProfileCompletionResponse=api.post("/profile/completion",ProfileCompletionRequest(idCardNumber.trim().uppercase(),mobileNumber.trim(),ethnicity,current,household));response.session.mobileTokens?.let(::saveTokens);state=state.copy(profile=response.profile,notice="信息已完善");bootstrap()}
    fun revealSensitiveProfileField(field:String,password:String,onSuccess:(String)->Unit)=launch{val response:SensitiveProfileFieldResponse=api.post("/profile/sensitive-fields/$field/reveal",buildJsonObject{put("password",password)});onSuccess(response.value);state=state.copy(notice="身份已验证，资料已复制")}
    fun loadAccounts(cursor:String?=null,query:String=currentQuery,lifecycle:String=currentLifecycle,role:String=currentRole,org:String=currentOrg)=launch{if(cursor==null)state=state.copy(selected=emptySet());currentQuery=query;currentLifecycle=lifecycle;currentRole=role;currentOrg=org;if(state.orgUnits.isEmpty())loadOrganizationsInline();val page:AccountPage=api.get(accountPath(cursor));state=state.copy(accounts=if(cursor==null)page.items else state.accounts+page.items,nextCursor=page.nextCursor)}
    private fun accountPath(cursor:String?=null)=buildString{append("/system/accounts?limit=50");cursor?.let{append("&cursor=").append(enc(it))};if(currentQuery.isNotBlank())append("&q=").append(enc(currentQuery));if(currentLifecycle.isNotBlank())append("&lifecycleStatus=").append(enc(currentLifecycle));if(currentRole.isNotBlank())append("&roleCode=").append(enc(currentRole));if(currentOrg.isNotBlank())append("&orgUnitId=").append(enc(currentOrg))}
    fun loadAccountDetail(id:String)=launch{if(state.orgUnits.isEmpty())loadOrganizationsInline();state=state.copy(accountDetail=api.get("/system/accounts/$id"))}
    fun clearAccountDetail(){state=state.copy(accountDetail=null)}
    fun updateAccount(login:String,name:String,gender:String?,reason:String,idCardNumber:String?=null,mobileNumber:String?=null,currentAddress:ProfileAddressInput?=null,householdAddress:ProfileAddressInput?=null,onSuccess:()->Unit={})=launch{val detail=state.accountDetail?:return@launch;val updated:AccountDetail=api.patch("/system/accounts/${detail.id}",buildJsonObject{put("loginName",login);put("displayName",name);if(gender.isNullOrBlank())put("gender",JsonNull)else put("gender",gender);if(detail.profileCompleted&&idCardNumber!=null&&mobileNumber!=null&&currentAddress!=null&&householdAddress!=null){put("idCardNumber",idCardNumber);put("mobileNumber",mobileNumber);put("currentAddress",api.json.encodeToJsonElement(ProfileAddressInput.serializer(),currentAddress));put("householdAddress",api.json.encodeToJsonElement(ProfileAddressInput.serializer(),householdAddress))};put("expectedVersion",detail.version);put("reason",reason)});state=state.copy(accountDetail=updated,accounts=state.accounts.map{if(it.id==updated.id)updated.toSummary()else it},notice="账户资料已更正");onSuccess()}
    fun loadAccountAddressOptions(detail:AccountDetail)=launch{val provinces:List<AdministrativeDivision> = api.get("/reference/administrative-divisions?level=PROVINCE");val currentCities:List<AdministrativeDivision> = detail.currentAddress?.let{api.get("/reference/administrative-divisions?level=CITY&parentCode=${enc(it.provinceCode)}") }?:emptyList();val currentDistricts:List<AdministrativeDivision> = detail.currentAddress?.let{api.get("/reference/administrative-divisions?level=DISTRICT&parentCode=${enc(it.cityCode)}") }?:emptyList();val householdCities:List<AdministrativeDivision> = detail.householdAddress?.let{api.get("/reference/administrative-divisions?level=CITY&parentCode=${enc(it.provinceCode)}") }?:emptyList();val householdDistricts:List<AdministrativeDivision> = detail.householdAddress?.let{api.get("/reference/administrative-divisions?level=DISTRICT&parentCode=${enc(it.cityCode)}") }?:emptyList();state=state.copy(provinces=provinces,currentCities=currentCities,currentDistricts=currentDistricts,householdCities=householdCities,householdDistricts=householdDistricts)}
    fun changeEnrollment(classId:String,reason:String,onSuccess:()->Unit={})=launch{val detail=state.accountDetail?:return@launch;val updated:AccountDetail=api.post("/system/accounts/${detail.id}/enrollment-change",buildJsonObject{put("classId",classId);put("expectedVersion",detail.version);put("reason",reason)});state=state.copy(accountDetail=updated,accounts=state.accounts.map{if(it.id==updated.id)updated.toSummary()else it},notice="学籍变动已完成，该学生需要重新登录");onSuccess()}
    fun toggleAccount(id:String){state=state.copy(selected=state.selected.toMutableSet().apply{if(!add(id))remove(id)})}
    fun clearAccountSelection(){state=state.copy(selected=emptySet())}
    fun selectAllAccounts(query:String,lifecycle:String,role:String,org:String=currentOrg)=launch{
        currentQuery=query.trim();currentLifecycle=lifecycle;currentRole=role;currentOrg=org
        val all=mutableListOf<AccountSummary>();var cursor:String?=null
        do{
            val page:AccountPage=api.get(accountPath(cursor));all+=page.items
            if(all.size>5_000){state=state.copy(error="筛选结果超过 5000 个账户，请增加筛选条件后再全选");return@launch}
            cursor=page.nextCursor
        }while(cursor!=null)
        state=state.copy(accounts=all,nextCursor=null,selected=all.map{it.id}.toSet(),notice=if(currentQuery.isBlank()&&currentLifecycle.isBlank()&&currentRole.isBlank())"已选择全部账户，共 ${all.size} 个" else "已选择筛选后的全部账户，共 ${all.size} 个")
    }
    fun accountCommand(type:String,reason:String)=launch{
        if(state.selected.isEmpty()){state=state.copy(error="请先选择账户");return@launch}
        val result:JobAccepted=api.request("/system/accounts/commands/$type","POST",buildJsonObject{put("accountIds",JsonArray(state.selected.map(::JsonPrimitive)));put("expectedCount",state.selected.size);put("reason",reason)}.toString())
        state=state.copy(selected=emptySet(),pendingCredentialCommandId=if(type=="reset-password")result.jobId else state.pendingCredentialCommandId,notice=result.controlRequestId?.let{"等待另一位管理员在安全复核中确认"}?:"操作已完成")
        
        loadAccountsInline()
    }
    fun refreshCommandCredential()=launch{val id=state.pendingCredentialCommandId?:return@launch;val status:JobStatus=api.get("/system/account-commands/$id");if(status.state=="COMPLETED")downloadCommandCredentialInline(id)else state=state.copy(notice="操作状态：${UiText.label(status.state)}")}
    private suspend fun downloadCommandCredentialInline(id:String){val ticket:DownloadTicket=api.request("/system/account-commands/$id/credential-ticket","POST");state=state.copy(exportBytes=api.download(ticket.url),exportFileName="重置后的初始密码.csv",exportMime="text/csv",pendingCredentialCommandId=null,notice="重置后的初始密码文件已就绪")}
    fun createAccount(login:String,name:String,personNo:String,gender:String?,classId:String?,role:String,scopes:List<String>,reason:String,onSuccess:()->Unit={})=launch{val body=buildJsonObject{put("loginName",login);put("displayName",name);put("personNo",personNo);if(role=="USER"&&!gender.isNullOrBlank())put("gender",gender);if(role=="USER"&&classId!=null)put("classId",classId);put("roleAssignments",buildJsonArray{add(buildJsonObject{put("roleCode",role);put("scopeCeilings",buildJsonArray{scopes.forEach{add(buildJsonObject{put("orgUnitId",it)})}})})});put("reason",reason)};val response:JsonObject=api.request("/system/accounts","POST",body.toString());val password=response["credential"]?.jsonObject?.get("temporaryPassword")?.jsonPrimitive?.contentOrNull;state=state.copy(newAccountPassword=password,notice=if(password==null)"账户已创建" else null);onSuccess();loadAccountsInline()}

    fun clearNewAccountPassword(){state=state.copy(newAccountPassword=null)}
    fun downloadImportTemplate()=launch{state=state.copy(exportBytes=api.download("/system/account-import/template"),exportFileName="生化通账户导入模板.xlsx",exportMime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",notice="官方模板已就绪")}
    fun uploadImport(file:File)=launch{deleteImportCache();importCacheFile=file;try{val accepted=api.upload("/system/account-import/jobs",file);val result:ImportJob=api.get("/system/account-import/jobs/${accepted.jobId}");val errors:Page<ImportError> = api.get("/system/account-import/jobs/${accepted.jobId}/errors");state=state.copy(importJobId=result.id,importRevision=result.organizationRevision,importRowCount=result.rowCount,importState=result.state,importErrors=errors.items,notice="名单检查：${UiText.label(result.state)}")}finally{deleteImportCache()}}
    fun commitImport(reason:String)=launch{val id=state.importJobId?:return@launch;if(state.importState!="VALID")return@launch;api.request<JobAccepted>("/system/account-import/jobs/$id/commit","POST",buildJsonObject{put("validatedRevision",state.importRevision);put("expectedAccountCount",state.importRowCount);put("reason",reason)}.toString());state=state.copy(importState="COMMITTED",importErrors=emptyList(),notice="导入完成，请领取初始密码");loadAccountsInline()}
    fun downloadImportCredentials()=launch{val id=state.importJobId?:return@launch;if(state.importState!="COMMITTED")return@launch;val ticket:DownloadTicket=api.request("/system/account-import/jobs/$id/credential-ticket","POST");state=state.copy(exportBytes=api.download(ticket.url),exportFileName="导入账户的初始密码.csv",exportMime="text/csv",importJobId=null,notice="初始密码文件已就绪，请保存")}
    fun clearImport(){deleteImportCache();state=state.copy(importJobId=null,importState=null,importErrors=emptyList())}
    fun requestExport(reason:String)=launch{val count=if(state.selected.isNotEmpty())state.selected.size else countAllAccounts();if(count==0){state=state.copy(notice="当前筛选结果为空，无可导出账户");return@launch};val selection=if(state.selected.isNotEmpty())buildJsonObject{put("mode","SELECTED");put("accountIds",JsonArray(state.selected.map(::JsonPrimitive)))}else buildJsonObject{put("mode","FILTERED_ALL");put("filter",buildJsonObject{if(currentQuery.isNotBlank())put("q",currentQuery);if(currentLifecycle.isNotBlank())put("lifecycleStatus",buildJsonArray{add(currentLifecycle)});if(currentRole.isNotBlank())put("roleCodes",buildJsonArray{add(currentRole)});if(currentOrg.isNotBlank())put("orgUnitIds",buildJsonArray{add(currentOrg)})});put("expectedCount",count)};val result:JobAccepted=api.request("/system/account-export/jobs","POST",buildJsonObject{put("selection",selection);put("purpose",reason)}.toString());val status:JobStatus=api.get("/system/account-export/jobs/${result.jobId}");if(status.state!="READY"){state=state.copy(notice="导出任务状态：${UiText.label(status.state)}");return@launch};val ticket:DownloadTicket=api.request("/system/account-export/jobs/${result.jobId}/download-ticket","POST");state=state.copy(exportBytes=api.download(ticket.url),exportFileName="账户名单.csv",exportMime="text/csv",notice="导出文件已就绪，共 ${status.resultCount?:count} 条")}
    fun exportSaved(){state=state.copy(exportBytes=null,notice="文件已保存")}

    fun loadAssignments(accountId:String)=launch{state=state.copy(assignments=emptyList());state=state.copy(assignments=api.get("/system/accounts/$accountId/role-assignments"))}
    fun addAssignment(accountId:String,role:String,scopes:List<String>,reason:String)=launch{val body=buildJsonObject{put("roleCode",role);put("scopeCeilingOrgUnitIds",JsonArray(scopes.map(::JsonPrimitive)));put("validFrom",Instant.now().toString());put("reason",reason)};api.request<JsonObject>("/system/accounts/$accountId/role-assignments","POST",body.toString());state=state.copy(notice="授权请求已提交");loadAssignmentsInline(accountId)}
    fun revokeAssignment(id:String,reason:String,accountId:String="")=launch{api.request<JsonObject>("/system/role-assignments/$id/revoke","POST",buildJsonObject{put("reason",reason)}.toString());state=state.copy(notice="取消申请已提交");if(accountId.isNotBlank())loadAssignmentsInline(accountId)}
    fun loadEditorData()=launch{loadOrganizationsInline();val page:AccountPage=api.get("/system/accounts?limit=30");state=state.copy(accountOptions=page.items,accountOptionsNext=page.nextCursor)}
    fun searchAccountOptions(query:String,cursor:String?=null)=launch{val page:AccountPage=api.get("/system/accounts?limit=30&q=${enc(query)}"+(cursor?.let{"&cursor=${enc(it)}"}?:""));state=state.copy(accountOptions=if(cursor==null)page.items else state.accountOptions+page.items,accountOptionsNext=page.nextCursor)}
    fun reportError(message:String){state=state.copy(error=message)}
    fun clearFeedback(){state=state.copy(error=null,notice=null)}
    fun loadOrganizations()=launch{loadOrganizationsInline()}
    fun createOrg(type:String,parent:String?,code:String,name:String,reason:String,onSuccess:()->Unit={})=launch{val body=buildJsonObject{put("type",type);if(parent!=null)put("parentId",parent);put("code",code.ifBlank{"ORG-"+java.util.UUID.randomUUID().toString().take(8)});put("name",name);put("expectedVersion",0);put("reason",reason)};api.request<OrgUnit>("/system/org-units","POST",body.toString());state=state.copy(notice="已创建");onSuccess();loadOrganizationsInline()}
    fun updateOrg(unit:OrgUnit,name:String,reason:String,onSuccess:()->Unit={})=launch{val body=buildJsonObject{put("type",unit.type);unit.parentId?.let{put("parentId",it)};put("code",unit.code);put("name",name);put("expectedVersion",unit.version);put("reason",reason)};api.request<OrgUnit>("/system/org-units/${unit.id}","PATCH",body.toString());state=state.copy(notice="名称已保存");onSuccess();loadOrganizationsInline()}
    fun deleteOrg(id:String,reason:String)=launch{api.request<Unit>("/system/org-units/$id","DELETE",buildJsonObject{put("reason",reason)}.toString());state=state.copy(notice="组织已删除，可重新使用原名称和编号创建");loadOrganizationsInline()}
    fun loadAudit(code:String="")=launch{loadAuditInline(code)}
    fun loadAuditDetail(id:String)=launch{state=state.copy(auditDetail=api.get("/system/audit/events/$id"))}
    fun clearAuditDetail(){state=state.copy(auditDetail=null)}
    fun requestAuditExport(reason:String,code:String="")=launch{val now=Instant.now();val result:JobAccepted=api.request("/system/audit/export-jobs","POST",buildJsonObject{put("occurredFrom",now.minusSeconds(31L*86400).toString());put("occurredTo",now.toString());put("purpose",reason);if(code.isNotBlank())put("eventCodes",buildJsonArray{add(code)})}.toString());state=state.copy(auditExportJobId=result.jobId,notice=result.controlRequestId?.let{"等待另一位管理员确认导出申请"}?:"审计导出任务已创建")}
    fun refreshAuditExport()=launch{val id=state.auditExportJobId?:return@launch;val status:JobStatus=api.get("/system/audit/export-jobs/$id");if(status.state!="READY"){state=state.copy(notice="审计导出状态：${UiText.label(status.state)}");return@launch};val ticket:DownloadTicket=api.request("/system/audit/export-jobs/$id/download-ticket","POST");state=state.copy(exportBytes=api.download(ticket.url),exportFileName="操作记录.csv",exportMime="text/csv",auditExportJobId=null,notice="审计导出已就绪")}
    fun loadControls()=launch{loadControlsInline()}
    fun decideControl(id:String,version:Long,approve:Boolean,reason:String)=launch{api.request<JsonObject>("/system/control-requests/$id/${if(approve)"approve" else "reject"}","POST",buildJsonObject{put("expectedVersion",version);put("reason",reason)}.toString());state=state.copy(notice="复核已提交");loadControlsInline()}

    private suspend fun loadAccountsInline(){val page:AccountPage=api.get(accountPath());state=state.copy(accounts=page.items,nextCursor=page.nextCursor)}
    private suspend fun loadAssignmentsInline(id:String){state=state.copy(assignments=api.get("/system/accounts/$id/role-assignments"))}
    private suspend fun loadOrganizationsInline(){state=state.copy(orgUnits=api.get("/system/org-units"))}
    private suspend fun loadControlsInline(){val page:Page<JsonObject> = api.get("/system/control-requests");state=state.copy(controls=page.items)}
    private suspend fun loadAuditInline(code:String=""){val now=Instant.now();val query="?occurredFrom=${enc(now.minusSeconds(31L*86400).toString())}&occurredTo=${enc(now.toString())}"+(if(code.isBlank())"" else "&eventCode=${enc(code)}");val page:Page<AuditEvent> = api.get("/system/audit/events$query");state=state.copy(audits=page.items)}
    private suspend fun countAllAccounts():Int{var count=0;var cursor:String?=null;do{val page:AccountPage=api.get(accountPath(cursor));count+=page.items.size;cursor=page.nextCursor}while(cursor!=null);return count}
    private fun saveTokens(tokens:MobileTokens)=store.save(tokens.accessToken,tokens.refreshToken)
    private fun deleteImportCache(){importCacheFile?.delete();importCacheFile=null}
    private fun enc(value:String)=URLEncoder.encode(value,"UTF-8")
    private fun errorText(code:String):String { val text=UiText.label(code);return if(text==code)"操作未完成，请检查填写内容或稍后重试" else text }
    private fun AccountDetail.toSummary()=AccountSummary(id,loginName,displayName,personNo,gender,lifecycleStatus,securityStatus,roleCodes,passwordChangeRequired,version)
}

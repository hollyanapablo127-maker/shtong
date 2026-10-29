@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shtong.app

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

@Composable fun ShtongApp(vm:ShtongViewModel,obscured:Boolean){
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
        when(vm.state.phase){Phase.CHECKING->GateProgress();Phase.LOGIN->LoginScreen(vm);Phase.PASSWORD->ForcedPasswordScreen(vm);Phase.PROFILE_COMPLETION->ProfileCompletionScreen(vm);Phase.APP->Workspace(vm)}
        if(obscured)Surface(Modifier.fillMaxSize().semantics{contentDescription="应用已进入后台，受保护内容已隐藏"},color=MaterialTheme.colorScheme.background){Box(contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer){Icon(Icons.Outlined.Lock,"受保护",Modifier.padding(14.dp).size(30.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)};Text("生化通",style=MaterialTheme.typography.headlineMedium);Text("已隐藏受保护内容",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
    }
}

@Composable private fun GateProgress(){Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer){Text("生",Modifier.padding(horizontal=16.dp,vertical=12.dp),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)};CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=3.dp);Text("正在验证会话",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable private fun LoginScreen(vm:ShtongViewModel){var login by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};AuthFrame("生化通","账户、权限与审计工作区"){OutlinedTextField(login,{login=it},Modifier.fillMaxWidth(),label={Text("登录账号")},singleLine=true);OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("密码")},singleLine=true,visualTransformation=PasswordVisualTransformation());ErrorText(vm.state.error);Button({vm.login(login,password)},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!vm.state.busy){Text(if(vm.state.busy)"正在登录" else "登录")}}}
@Composable private fun ForcedPasswordScreen(vm:ShtongViewModel){var current by remember{mutableStateOf("")};var next by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};var local by remember{mutableStateOf<String?>(null)};val policy=meetsPasswordPolicy(next);AuthFrame("设置正式密码","首次登录或密码重置后必须先完成改密"){OutlinedTextField(current,{current=it},Modifier.fillMaxWidth(),label={Text("临时密码")},visualTransformation=PasswordVisualTransformation());OutlinedTextField(next,{next=it},Modifier.fillMaxWidth(),label={Text("新密码（至少 8 位，含大小写和数字）")},visualTransformation=PasswordVisualTransformation());OutlinedTextField(confirm,{confirm=it},Modifier.fillMaxWidth(),label={Text("确认新密码")},visualTransformation=PasswordVisualTransformation());ErrorText(local?:vm.state.error);Button({when{next!=confirm->local="两次输入的新密码不一致";!policy->local="密码至少 8 位，并同时包含大写字母、小写字母和数字";else->vm.changePassword(current,next)}},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!vm.state.busy){Text("更新密码")};TextButton(vm::logout,Modifier.fillMaxWidth()){Text("退出")}}}
@Composable private fun ProfileCompletionScreen(vm:ShtongViewModel){
    var idCard by remember{mutableStateOf("")};var mobile by remember{mutableStateOf("")};var ethnicity by remember{mutableStateOf(PROFILE_DEFAULT_ETHNICITY)}
    var currentProvince by remember{mutableStateOf("")};var currentCity by remember{mutableStateOf("")};var currentDistrict by remember{mutableStateOf("")};var currentDetail by remember{mutableStateOf("")}
    var householdProvince by remember{mutableStateOf("")};var householdCity by remember{mutableStateOf("")};var householdDistrict by remember{mutableStateOf("")};var householdDetail by remember{mutableStateOf("")}
    var localError by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(Unit){vm.loadCompletionOptions()}
    val ready=ethnicity.isNotBlank()&&idCard.matches(Regex("^[0-9]{17}[0-9Xx]$"))&&mobile.matches(Regex("^1[3-9][0-9]{9}$"))&&listOf(currentProvince,currentCity,currentDistrict,householdProvince,householdCity,householdDistrict).all(String::isNotBlank)&&currentDetail.trim().length>=2&&householdDetail.trim().length>=2
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("完善个人信息",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("首次设置正式密码后需完成以下资料，保存后才能进入工作台。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        vm.state.profile?.let{ProfileLine("姓名（不可修改）",it.displayName);ProfileLine("学号（不可修改）",it.personNo);it.organizationPath.forEach{item->ProfileLine(UiText.label(item.type),item.name)}}
        HorizontalDivider();Text("身份与联系方式",fontWeight=FontWeight.Bold,fontSize=19.sp)
        Field("身份证号（必填）",idCard){idCard=it.take(18)};ProfileLine("年龄（自动计算）",derivedAge(idCard)?.toString() ?: "输入身份证号后自动计算");ChoiceField("民族（必填）",ethnicity,ETHNICITY_OPTIONS){ethnicity=it};Field("手机号（必填）",mobile){mobile=it.filter(Char::isDigit).take(11)}
        ProfileAddressFields("现家庭住址",vm.state.provinces,vm.state.currentCities,vm.state.currentDistricts,currentProvince,currentCity,currentDistrict,currentDetail,{currentProvince=it;currentCity="";currentDistrict="";vm.selectProfileProvince("current",it)},{currentCity=it;currentDistrict="";vm.selectProfileCity("current",it)},{currentDistrict=it},{currentDetail=it})
        ProfileAddressFields("户籍地址（按户口簿或身份证填写）",vm.state.provinces,vm.state.householdCities,vm.state.householdDistricts,householdProvince,householdCity,householdDistrict,householdDetail,{householdProvince=it;householdCity="";householdDistrict="";vm.selectProfileProvince("household",it)},{householdCity=it;householdDistrict="";vm.selectProfileCity("household",it)},{householdDistrict=it},{householdDetail=it})
        ErrorText(localError?:vm.state.error)
        Button({if(!ready)localError="请完整填写有效的身份证号、民族、手机号和两处地址" else vm.completeProfile(idCard,mobile,ethnicity,ProfileAddressInput(currentProvince,currentCity,currentDistrict,currentDetail.trim()),ProfileAddressInput(householdProvince,householdCity,householdDistrict,householdDetail.trim()))},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!vm.state.busy){Text(if(vm.state.busy)"正在保存" else "保存并进入工作台")}
        TextButton(vm::logout,Modifier.fillMaxWidth(),enabled=!vm.state.busy){Text("退出登录")}
    }
}
internal const val PROFILE_DEFAULT_ETHNICITY = ""
private val ETHNICITY_OPTIONS = listOf("汉族","蒙古族","回族","藏族","维吾尔族","苗族","彝族","壮族","布依族","朝鲜族","满族","侗族","瑶族","白族","土家族","哈尼族","哈萨克族","傣族","黎族","傈僳族","佤族","畲族","高山族","拉祜族","水族","东乡族","纳西族","景颇族","柯尔克孜族","土族","达斡尔族","仫佬族","羌族","布朗族","撒拉族","毛难族","仡佬族","锡伯族","阿昌族","普米族","塔吉克族","怒族","乌孜别克族","俄罗斯族","鄂温克族","崩龙族","保安族","裕固族","京族","塔塔尔族","独龙族","鄂伦春族","赫哲族","门巴族","珞巴族","基诺族").map { it to it }
private fun derivedAge(id:String):Int? { if(!id.matches(Regex("^\\d{17}[0-9Xx]$"))) return null; return try { val birth=java.time.LocalDate.of(id.substring(6,10).toInt(),id.substring(10,12).toInt(),id.substring(12,14).toInt()); java.time.Period.between(birth,java.time.LocalDate.now()).years } catch (_:Exception) { null } }
@Composable private fun ProfileAddressFields(title:String,provinces:List<AdministrativeDivision>,cities:List<AdministrativeDivision>,districts:List<AdministrativeDivision>,province:String,city:String,district:String,detail:String,onProvince:(String)->Unit,onCity:(String)->Unit,onDistrict:(String)->Unit,onDetail:(String)->Unit){
    HorizontalDivider();Text(title,fontWeight=FontWeight.Bold,fontSize=19.sp)
    ChoiceField("省/自治区/直辖市",province,provinces.map{it.code to it.name},change=onProvince)
    ChoiceField("市",city,cities.map{it.code to it.name},enabled=province.isNotBlank(),change=onCity)
    ChoiceField("区/县",district,districts.map{it.code to it.name},enabled=city.isNotBlank(),change=onDistrict)
    OutlinedTextField(detail,onDetail,Modifier.fillMaxWidth(),label={Text("详细地址（必填）")},minLines=2,maxLines=4)
}
@Composable private fun AuthFrame(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit){
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(horizontal=22.dp,vertical=20.dp),contentAlignment=Alignment.Center){
        Column(Modifier.widthIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)){
                Surface(color=MaterialTheme.colorScheme.primary,shape=MaterialTheme.shapes.small){Text("生",Modifier.padding(horizontal=13.dp,vertical=9.dp),color=MaterialTheme.colorScheme.onPrimary,style=MaterialTheme.typography.titleLarge)}
                Column{Text("生化通",style=MaterialTheme.typography.titleLarge);Text("统一校园工作台",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=2.dp){
                Column(Modifier.padding(horizontal=22.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text(title,style=MaterialTheme.typography.displaySmall);Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant);HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant);content()}
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Icon(Icons.Outlined.Security,"安全连接",Modifier.size(17.dp),tint=MaterialTheme.colorScheme.tertiary);Text("账号状态由生化通统一保护",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

data class NavItem(val route:Route,val label:String,val icon:androidx.compose.ui.graphics.vector.ImageVector,val external:Boolean=false)
@Composable
private fun Workspace(vm: ShtongViewModel) {
    if(vm.state.route==Route.MEDICAL_INSURANCE){MedicalInsuranceScreen(vm);return}
    // The bottom navigation is intentionally stable: modules belong to the
    // workbench and must not become peer-level tabs as more modules are added.
    val nav = listOf(
        NavItem(Route.HOME, "工作台", Icons.Outlined.SpaceDashboard),
        NavItem(Route.PROFILE, "我的", Icons.Outlined.Person),
    )

    fun selected(item: NavItem): Boolean =
        vm.state.route == item.route ||
            (item.route == Route.HOME && vm.state.route in setOf(Route.ACCOUNTS,Route.GRADUATES,Route.AUTHORIZATION,Route.ORGANIZATION,Route.AUDIT,Route.MEDICAL_INSURANCE)) ||
            (item.route == Route.PROFILE && vm.state.route == Route.PASSWORD)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.background(MaterialTheme.colorScheme.background)) {
                NavigationRail(containerColor=MaterialTheme.colorScheme.surface) {
                    Surface(Modifier.padding(vertical=14.dp),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primary){Text("生",Modifier.padding(horizontal=11.dp,vertical=7.dp),color=MaterialTheme.colorScheme.onPrimary,fontWeight=FontWeight.Bold)}
                    nav.forEach { item ->
                        NavigationRailItem(
                            selected = selected(item),
                            onClick = { if (item.route == Route.MEDICAL_INSURANCE) vm.openMedicalInsurance() else vm.navigate(item.route) },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = false,
                        onClick = vm::logout,
                        icon = { Icon(Icons.AutoMirrored.Outlined.Logout, "退出") },
                        label = { Text("退出") },
                    )
                }
                VerticalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                WorkspaceContent(vm, Modifier.weight(1f))
            }
        } else {
            Scaffold(
                containerColor=MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = selected(item),
                                onClick = { if (item.route == Route.MEDICAL_INSURANCE) vm.openMedicalInsurance() else vm.navigate(item.route) },
                                icon = { Icon(item.icon, item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
                topBar = {
                    TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface,titleContentColor=MaterialTheme.colorScheme.onSurface),
                        navigationIcon = {
                            if (vm.state.route != Route.HOME) {
                                IconButton(onClick = { vm.navigate(Route.HOME) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "返回工作台" }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回工作台")
                                }
                            }
                        },
                        title = { Text(title(vm.state.route),style=MaterialTheme.typography.titleLarge) },
                        actions = {
                            IconButton(
                                onClick = vm::logout,
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "退出登录"
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Logout, null)
                            }
                        },
                    )
                },
            ) { padding ->
                WorkspaceContent(vm, Modifier.padding(padding))
            }
        }
    }
}
@Composable private fun WorkspaceContent(vm:ShtongViewModel,modifier:Modifier=Modifier){
    Box(modifier.fillMaxSize()){
        when(vm.state.route){Route.HOME->ModuleWorkbench(vm);Route.ACCOUNTS->AccountsScreen(vm);Route.GRADUATES->AccountsScreen(vm,graduates=true);Route.AUTHORIZATION->AuthorizationScreen(vm);Route.ORGANIZATION->OrganizationScreen(vm);Route.AUDIT->AuditScreen(vm);Route.MEDICAL_INSURANCE->MedicalInsuranceScreen(vm);Route.PROFILE->ProfileScreen(vm);Route.PASSWORD->AccountPasswordScreen(vm)}
        vm.state.error?.let { Text(it,Modifier.align(Alignment.TopCenter).padding(12.dp),color=MaterialTheme.colorScheme.error) }
        if(vm.state.busy)LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        vm.state.accountDetail?.let{detail->AccountDetailDialog(vm,detail)}
        vm.state.newAccountPassword?.let{password->NewAccountPasswordDialog(password,vm::clearNewAccountPassword)}
        if(vm.state.importJobId!=null&&vm.state.importState!="COMMITTED")AlertDialog(
            onDismissRequest={if(!vm.state.busy)vm.clearImport()},
            title={Text("名单检查：${UiText.label(vm.state.importState.orEmpty())}")},
            text={Column(Modifier.verticalScroll(rememberScrollState())){ErrorText(vm.state.error);Text("账户行数：${vm.state.importRowCount}");vm.state.importErrors.forEach{Text("${UiText.label(it.sheet)} 第 ${it.row} 行 · ${UiText.label(it.field)}：${UiText.issue(it.code)}",color=MaterialTheme.colorScheme.error)}}},
            confirmButton={if(vm.state.importState=="VALID")Button({vm.commitImport("确认批量导入账户")},enabled=!vm.state.busy){Text(if(vm.state.busy)"正在导入" else "确认提交")}else TextButton(vm::clearImport,enabled=!vm.state.busy){Text("关闭")}},
            dismissButton={if(vm.state.importState=="VALID")TextButton(vm::clearImport,enabled=!vm.state.busy){Text("取消")}}
        )
    }
}
@Composable private fun ModuleWorkbench(vm:ShtongViewModel){
    val codes=vm.state.bootstrap?.activeModules?.map{it.code}?.toSet().orEmpty()
    val items=buildList{
        if("system.accounts" in codes)add(NavItem(Route.ACCOUNTS,"账户管理",Icons.Outlined.Group))
        if("system.graduates" in codes)add(NavItem(Route.GRADUATES,"毕业生管理",Icons.Outlined.School))
        if("system.authorization" in codes)add(NavItem(Route.AUTHORIZATION,"身份与权限",Icons.Outlined.AdminPanelSettings))
        if("system.organization" in codes)add(NavItem(Route.ORGANIZATION,"学校与班级",Icons.Outlined.AccountTree))
        if("system.audit" in codes)add(NavItem(Route.AUDIT,"操作记录",Icons.AutoMirrored.Outlined.ReceiptLong))
        if("medical-insurance" in codes)add(NavItem(Route.MEDICAL_INSURANCE,"医保信息系统",Icons.Outlined.LocalHospital))
    }
    fun description(route:Route)=when(route){Route.ACCOUNTS->"账户创建、状态与批量维护";Route.GRADUATES->"毕业档案查询与导出";Route.AUTHORIZATION->"身份授权与管辖范围";Route.ORGANIZATION->"学校、学院、年级与班级";Route.AUDIT->"安全操作与访问记录";Route.MEDICAL_INSURANCE->"学生参保、审核与覆盖率";else->"进入模块"}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.background).padding(horizontal=20.dp,vertical=22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("工作台",style=MaterialTheme.typography.headlineMedium);Text("选择已授权的业务模块",color=MaterialTheme.colorScheme.onSurfaceVariant)};Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.tertiaryContainer){Icon(Icons.Outlined.GridView,"模块",Modifier.padding(11.dp).size(25.dp),tint=MaterialTheme.colorScheme.onTertiaryContainer)}}
        HorizontalDivider(Modifier.padding(vertical=3.dp),color=MaterialTheme.colorScheme.outlineVariant)
        Text("业务模块",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(items.isEmpty()) EmptyWorkspace() else items.forEach{item->
            Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().heightIn(min=96.dp).clickable{if(item.route==Route.MEDICAL_INSURANCE)vm.openMedicalInsurance()else vm.navigate(item.route)}.semantics{contentDescription="打开${item.label}"}){
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    Surface(color=if(item.route==Route.MEDICAL_INSURANCE)MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small){Icon(item.icon,item.label,tint=if(item.route==Route.MEDICAL_INSURANCE)MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.padding(11.dp).size(27.dp))}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(item.label,style=MaterialTheme.typography.titleMedium);Text(description(item.route),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward,"进入",tint=MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("个人资料与账户安全设置可在“我的”中查看。",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp,bottom=12.dp))
    }
}
private fun title(route:Route)=when(route){Route.HOME->"工作台";Route.ACCOUNTS->"账户管理";Route.GRADUATES->"毕业生管理";Route.AUTHORIZATION->"身份与权限";Route.ORGANIZATION->"学校与班级";Route.AUDIT->"操作记录";Route.MEDICAL_INSURANCE->"医保信息系统";Route.PROFILE->"个人中心";Route.PASSWORD->"修改密码"}
@Composable private fun MedicalInsuranceScreen(vm:ShtongViewModel){
    val context=LocalContext.current
    val moduleUrl=vm.state.medicalInsuranceUrl
    if(moduleUrl.isNullOrBlank()){
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalAlignment=Alignment.Start){
            Text("医保信息系统",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("使用生化通当前登录身份进入医保信息系统。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            ErrorText(vm.state.error)
            Button({vm.openMedicalInsurance()},enabled=!vm.state.busy,modifier=Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.LocalHospital,null);Spacer(Modifier.width(6.dp));Text("进入医保信息系统")}
        }
        return
    }
    val origin=remember(moduleUrl){
        runCatching {
            android.net.Uri.parse(moduleUrl).let { parsed -> "${parsed.scheme}://${parsed.authority}" }
        }.getOrNull().orEmpty()
    }
    val exitUrl=remember(origin){if(origin.isBlank())"" else "$origin/medical-insurance/exit/?embedded=1"}
    var webView by remember{mutableStateOf<WebView?>(null)}
    var loading by remember{mutableStateOf(true)}
    var leaving by remember{mutableStateOf(false)}
    var sidebarReady by remember{mutableStateOf(false)}
    var sidebarOpen by remember{mutableStateOf(false)}
    var navigationGeneration by remember{mutableIntStateOf(0)}
    var fileChooserClient by remember{mutableStateOf<MedicalFileChooserChromeClient?>(null)}
    var fileSelectionError by remember{mutableStateOf<String?>(null)}
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->fileChooserClient?.deliverResult(result.resultCode,result.data)}
    val scope=rememberCoroutineScope()
    fun leave(){if(leaving)return;leaving=true;if(exitUrl.isBlank()){vm.closeMedicalInsurance();return};webView?.loadUrl(exitUrl)?:vm.closeMedicalInsurance();scope.launch{delay(2200);if(leaving)vm.closeMedicalInsurance()}}
    fun awaitSidebarReady(view:WebView,generation:Int,attempt:Int=0){
        if(leaving||generation!=navigationGeneration)return
        view.evaluateJavascript(MEDICAL_SIDEBAR_READY_SCRIPT){result->
            if(leaving||generation!=navigationGeneration)return@evaluateJavascript
            if(sidebarReadyState(result))sidebarReady=true
            else if(attempt<30)view.postDelayed({awaitSidebarReady(view,generation,attempt+1)},100L)
        }
    }
    fun toggleSidebar(attempt:Int=0){
        val view=webView?:return
        view.evaluateJavascript(MEDICAL_SIDEBAR_TOGGLE_SCRIPT){result->
            val open=sidebarOpenState(result)
            if(open==null&&attempt<3)view.postDelayed({toggleSidebar(attempt+1)},150L)
            else if(open!=null)sidebarOpen=open
        }
    }
    BackHandler(enabled=!leaving){if(webView?.canGoBack()==true)webView?.goBack()else leave()}
    DisposableEffect(Unit){onDispose{fileChooserClient?.cancelPending();fileChooserClient=null;clearMedicalUploadCache(context);webView?.removeJavascriptInterface(MEDICAL_SIDEBAR_BRIDGE_NAME);webView?.stopLoading();webView?.loadUrl("about:blank");webView?.clearHistory();webView=null}}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
        Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp){Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal=8.dp).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){val sidebarLabel=when{!sidebarReady->"医保模块菜单正在加载";sidebarOpen->"关闭医保模块菜单";else->"打开医保模块菜单"};IconButton(onClick={toggleSidebar()},enabled=sidebarReady&&!leaving,modifier=Modifier.size(48.dp).semantics{contentDescription=sidebarLabel}){Icon(if(sidebarOpen)Icons.Outlined.Close else Icons.Outlined.Menu,sidebarLabel)};Spacer(Modifier.weight(1f));TextButton(::leave,enabled=!leaving,modifier=Modifier.heightIn(min=48.dp)){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(5.dp));Text(if(leaving)"正在返回" else "返回工作台")}}}
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        // The system picker returns a scoped content:// URI.
                        // Direct file-system access remains disabled.
                        settings.allowContentAccess = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = false
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.setSupportZoom(false)
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        val moduleView = this
                        addJavascriptInterface(
                            MedicalSidebarStateBridge { open -> moduleView.post { sidebarOpen = open } },
                            MEDICAL_SIDEBAR_BRIDGE_NAME,
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                navigationGeneration += 1
                                sidebarReady = false
                                sidebarOpen = false
                                loading = true
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                if(leaving && (url.substringBefore('?').trimEnd('/') == exitUrl.substringBefore('?').trimEnd('/') || view.title?.contains("安全返回") == true)) {
                                    vm.closeMedicalInsurance()
                                    return
                                }
                                view.evaluateJavascript("document.querySelectorAll('.mobile-nav-toggle').forEach(function(button){button.style.setProperty('display','none','important');});",null)
                                sidebarOpen = false
                                loading = false
                                awaitSidebarReady(view,navigationGeneration)
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url
                                return target.scheme !in listOf("http", "https") ||
                                    (origin.isNotBlank() && "${target.scheme}://${target.authority}" != origin)
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) { loading = false; if (leaving) vm.closeMedicalInsurance() }
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                                if (request.isForMainFrame && leaving) vm.closeMedicalInsurance()
                            }
                        }
                        MedicalFileChooserChromeClient(
                            launchPicker = { intent ->
                                clearMedicalUploadCache(context)
                                runCatching { filePicker.launch(intent); true }.getOrDefault(false)
                            },
                            transformUris = { selected -> normalizeMedicalUploadUris(context, selected) },
                            onInvalidSelection = { fileSelectionError = "所选文件无法读取，请选择 PDF、JPG、PNG 或 WebP 格式的缴费凭证。" },
                        ).also { chooser ->
                            webChromeClient = chooser
                            fileChooserClient = chooser
                        }
                        webView = this
                        loadUrl(moduleUrl)
                    }
                },
                update = { view -> if (view.url == null) view.loadUrl(moduleUrl) },
                modifier = Modifier.fillMaxSize(),
            )
            fileSelectionError?.let { message ->
                Surface(
                    color=MaterialTheme.colorScheme.errorContainer,
                    modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp).semantics{liveRegion=androidx.compose.ui.semantics.LiveRegionMode.Assertive},
                ){
                    Text(message,color=MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.padding(12.dp))
                }
            }
            if(loading)Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        }
    }
}
@Composable private fun EmptyWorkspace(){Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.SpaceDashboard,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(12.dp));Text("当前没有已开放的业务模块",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("可在个人中心维护资料、修改密码或退出。",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable private fun AccountsScreen(vm:ShtongViewModel,graduates:Boolean=false){
    val lifecycle=if(graduates)"GRADUATED" else "ACTIVE"
    var org by remember(graduates){mutableStateOf("")}
    val context=LocalContext.current
    var add by remember{mutableStateOf(false)};var pending by remember{mutableStateOf<String?>(null)};var reason by remember{mutableStateOf("账户维护")};var query by remember{mutableStateOf("")};var role by remember{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let{copyToCache(context,it)?.let(vm::uploadImport)?:vm.reportError("无法读取所选文件，请重新选择")}}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")){uri->val bytes=vm.state.exportBytes;if(uri!=null&&bytes!=null){runCatching{val stream=context.contentResolver.openOutputStream(uri)?:error("保存失败");stream.use{it.write(bytes)}}.onSuccess{vm.exportSaved()}.onFailure{vm.reportError("文件未保存，请选择可写入的位置重试")}}}
    Column(Modifier.fillMaxSize()){
        CommandFeedback(vm.state)
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("搜索登录账号、姓名或学工号")},enabled=!vm.state.busy,singleLine=true,trailingIcon={IconButton({vm.loadAccounts(query=query,lifecycle=lifecycle,role=role)}){Icon(Icons.Outlined.Search,"查询账户")}})
            ChoiceField("学院、年级或班级",org,listOf("" to "全部组织")+vm.state.orgUnits.map{it.id to orgName(it.id,vm.state.orgUnits)},enabled=!vm.state.busy){org=it;vm.loadAccounts(query=query,lifecycle=lifecycle,role=role,org=it)}
            if(!graduates)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("","USER","TEACHER_ADMIN","STUDENT_ADMIN","MEDICAL_INSURANCE_ADMIN","SYSTEM_ADMIN").forEach{value->FilterChip(role==value,{role=value;vm.loadAccounts(query=query,lifecycle=lifecycle,role=value)},{Text(if(value.isBlank())"全部身份" else UiText.label(value))},enabled=!vm.state.busy)}}
        }
    if(vm.state.selected.isEmpty())OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth().padding(horizontal=12.dp),label={Text("导出用途")},enabled=!vm.state.busy)
    if(vm.state.selected.isNotEmpty())Surface(color=MaterialTheme.colorScheme.secondaryContainer){Column(Modifier.fillMaxWidth().padding(12.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("已选 ${vm.state.selected.size} 项",Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton(vm::clearAccountSelection){Text("清除选择")}};OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth(),label={Text("操作原因")});Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){if(!graduates){ActionButton("重置",Icons.Outlined.Key){pending="reset-password"};ActionButton("毕业",Icons.Outlined.School){pending="graduate"};ActionButton("删除",Icons.Outlined.Delete){pending="delete"}};ActionButton("导出",Icons.Outlined.Download){vm.requestExport(reason)}}}}
        Row(Modifier.fillMaxWidth().padding(12.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){if(!graduates)FilledTonalButton({add=true},Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.PersonAdd,null);Spacer(Modifier.width(6.dp));Text("新增")};FilledTonalButton({vm.selectAllAccounts(query,lifecycle,role)},Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.SelectAll,null);Spacer(Modifier.width(6.dp));Text(if(query.isBlank()&&org.isBlank()&&role.isBlank())"全选全部账户" else "全选筛选结果")};if(!graduates)FilledTonalButton(vm::downloadImportTemplate,Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.Description,null);Spacer(Modifier.width(6.dp));Text("模板")};if(!graduates)FilledTonalButton({picker.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.UploadFile,null);Spacer(Modifier.width(6.dp));Text("导入")};FilledTonalButton({vm.requestExport(reason)},Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.Download,null);Spacer(Modifier.width(6.dp));Text(if(vm.state.selected.isEmpty())"导出当前全部" else "导出已选")}}
        if(!graduates&&vm.state.importState=="COMMITTED"&&vm.state.importJobId!=null)OutlinedButton(vm::downloadImportCredentials,Modifier.padding(horizontal=12.dp).fillMaxWidth().heightIn(min=48.dp),enabled=!vm.state.busy){Text("领取导入账户的初始密码（仅一次）")}
        if(!graduates)vm.state.pendingCredentialCommandId?.let{OutlinedButton(vm::refreshCommandCredential,Modifier.padding(horizontal=12.dp).fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.Refresh,null);Spacer(Modifier.width(6.dp));Text("刷新并领取初始密码")}}
        if(vm.state.exportBytes!=null)Button({saver.launch(vm.state.exportFileName)},Modifier.padding(horizontal=12.dp).fillMaxWidth().heightIn(min=48.dp)){Text("保存 ${vm.state.exportFileName}")}
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(vm.state.accounts,key={it.id}){account->AccountRow(account,vm.state.selected.contains(account.id),{vm.toggleAccount(account.id)},{vm.loadAccountDetail(account.id)})};if(vm.state.nextCursor!=null)item{OutlinedButton({vm.loadAccounts(vm.state.nextCursor,query,lifecycle,role)},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("下一页")}}}
    }
    pending?.let{command->val label=mapOf("reset-password" to "重置密码","graduate" to "移入毕业生","delete" to "删除账户")[command].orEmpty();AlertDialog({pending=null},title={Text("确认$label")},text={Column{Text("将处理已选的 ${vm.state.selected.size} 人。毕业或删除后这些账户将无法登录。");Field("操作原因",reason){reason=it}}},confirmButton={Button({vm.accountCommand(command,reason);pending=null},enabled=!vm.state.busy&&reason.length>=2){Text("确认$label")}},dismissButton={TextButton({pending=null}){Text("取消")}})}
    if(add)CreateAccountDialog(vm,{add=false}){login,name,no,gender,classId,newRole,scopes->vm.createAccount(login,name,no,gender,classId,newRole,scopes,reason){add=false}}
}
@Composable private fun AccountRow(account:AccountSummary,selected:Boolean,onSelect:()->Unit,onDetail:()->Unit){Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small,tonalElevation=if(selected)4.dp else 0.dp,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(selected,{onSelect()});Column(Modifier.weight(1f).clickable(onClick=onSelect).padding(vertical=4.dp)){Text(account.displayName,fontWeight=FontWeight.Bold);Text("${account.loginName} · ${account.personNo}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(account.roleCodes.joinToString(" / "){UiText.label(it)},fontSize=12.sp)};IconButton(onDetail,Modifier.size(48.dp)){Icon(Icons.Outlined.Info,"查看 ${account.displayName} 详情")}}}}
@Composable private fun CreateAccountDialog(vm:ShtongViewModel,close:()->Unit,submit:(String,String,String,String?,String?,String,List<String>)->Unit){
    var login by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var no by remember{mutableStateOf("")};var gender by remember{mutableStateOf("")};var classId by remember{mutableStateOf("")};var role by remember{mutableStateOf("USER")};var scopes by remember{mutableStateOf(setOf<String>())}
    LaunchedEffect(Unit){vm.loadEditorData()}
    AlertDialog(onDismissRequest={if(!vm.state.busy)close()},title={Text("新增账户")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        ErrorText(vm.state.error);Field("姓名",name){name=it};Field("学工号",no){no=it};Field("登录账号",login){login=it}
        ChoiceField("身份",role,listOf("USER","TEACHER_ADMIN","STUDENT_ADMIN","MEDICAL_INSURANCE_ADMIN").map{it to UiText.label(it)}){role=it;scopes=emptySet();if(it!="USER"){gender="";classId=""}}
        if(role=="USER")ChoiceField("性别（选填）",gender,listOf("" to "未填写","MALE" to "男","FEMALE" to "女","OTHER" to "其他")){gender=it}
        if(role=="USER")ChoiceField("所属班级（必选）",classId,listOf("" to "未选择")+vm.state.orgUnits.filter{it.type=="CLASS"&&it.status=="ACTIVE"}.map{it.id to orgName(it.id,vm.state.orgUnits)}){classId=it}
        ScopeChoices(vm.state.orgUnits,role,scopes){scopes=it}
    }},confirmButton={Button({submit(login,name,no,gender.ifBlank{null},classId.ifBlank{null},role,scopes.toList())},enabled=!vm.state.busy&&login.isNotBlank()&&name.isNotBlank()&&no.isNotBlank()&&(if(role=="USER")classId.isNotBlank() else scopes.isNotEmpty())){Text(if(vm.state.busy)"正在创建" else "创建账户")}},dismissButton={TextButton(close,enabled=!vm.state.busy){Text("取消")}})
}

private fun orgName(id:String?,units:List<OrgUnit>):String{if(id.isNullOrBlank())return "未设置";val names=mutableListOf<String>();val seen=mutableSetOf<String>();var current=units.find{it.id==id};while(current!=null&&seen.add(current.id)){names.add(0,current.name);val parent=current.parentId;current=units.find{it.id==parent}};return names.joinToString(" / ").ifBlank{"组织已不可用"}}
@Composable private fun ChoiceField(label:String,value:String,options:List<Pair<String,String>>,enabled:Boolean=true,change:(String)->Unit){
    var opened by remember{mutableStateOf(false)};var query by remember{mutableStateOf("")}
    OutlinedButton({opened=true},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled){Text("$label：${options.find{it.first==value}?.second?:"请选择"}",Modifier.weight(1f));Icon(Icons.Outlined.ArrowDropDown,null)}
    if(opened)AlertDialog({opened=false},title={Text(label)},text={Column{Field("按名称查找",query){query=it};LazyColumn(Modifier.heightIn(max=360.dp)){items(options.filter{it.second.contains(query)},key={it.first}){item->TextButton({change(item.first);opened=false;query=""},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled){Text(item.second,Modifier.fillMaxWidth())}}}}},confirmButton={TextButton({opened=false}){Text("关闭")}})
}
@Composable private fun ScopeChoices(units:List<OrgUnit>,role:String,selected:Set<String>,change:(Set<String>)->Unit){
    if(role!="TEACHER_ADMIN"&&role!="STUDENT_ADMIN"&&role!="MEDICAL_INSURANCE_ADMIN")return
    var query by remember{mutableStateOf("")}
    Text("管理范围（已选 ${selected.size} 项）",fontWeight=FontWeight.Bold);Field("查找管理范围",query){query=it}
    val options=units.filter{it.status=="ACTIVE"&&(if(role=="TEACHER_ADMIN")it.type=="GRADE" else it.type in listOf("COLLEGE","GRADE","CLASS"))&&orgName(it.id,units).contains(query)}
    if(options.isEmpty())Text("没有可选范围")
    Column(Modifier.heightIn(max=240.dp).verticalScroll(rememberScrollState())){options.forEach{unit->Row(Modifier.fillMaxWidth().clickable{change(if(unit.id in selected)selected-unit.id else selected+unit.id)}.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(unit.id in selected,{change(if(it)selected+unit.id else selected-unit.id)});Text(orgName(unit.id,units),Modifier.weight(1f))}}}
}
@Composable private fun AccountChoice(vm:ShtongViewModel,id:String,change:(String)->Unit){
    var query by remember{mutableStateOf("")}
    Field("姓名、登录账号或学工号",query){query=it}
    OutlinedButton({vm.searchAccountOptions(query)},enabled=!vm.state.busy){Text("查找账户")}
    ChoiceField("选择账户",id,vm.state.accountOptions.map{it.id to "${it.displayName} · ${it.loginName} · ${it.personNo}"},enabled=!vm.state.busy,change=change)
    if(vm.state.accountOptionsNext!=null)TextButton({vm.searchAccountOptions(query,vm.state.accountOptionsNext)},enabled=!vm.state.busy){Text("更多账户")}
}
@Composable private fun AuthorizationScreen(vm:ShtongViewModel){
    var account by remember{mutableStateOf("")};var role by remember{mutableStateOf("TEACHER_ADMIN")};var scopes by remember{mutableStateOf(setOf<String>())};var reason by remember{mutableStateOf("调整账户身份与范围")};var controls by remember{mutableStateOf(false)};var revoke by remember{mutableStateOf<RoleAssignment?>(null)}
    LaunchedEffect(Unit){vm.loadEditorData()}
    Column(Modifier.fillMaxSize()){SingleChoiceSegmentedButtonRow(Modifier.padding(12.dp)){SegmentedButton(!controls,{controls=false},SegmentedButtonDefaults.itemShape(0,2)){Text("身份与范围")};SegmentedButton(controls,{controls=true;vm.loadControls()},SegmentedButtonDefaults.itemShape(1,2)){Text("安全复核")}}
        CommandFeedback(vm.state)
        if(controls)ControlList(vm,reason)else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            AccountChoice(vm,account){account=it;vm.loadAssignments(it)}
            if(account.isNotBlank()){
                Text("现有身份",fontWeight=FontWeight.Bold)
                vm.state.assignments.forEach{item->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("${UiText.label(item.roleCode)} · ${UiText.label(item.status)}");Text(item.scopeCeilings.joinToString("；"){orgName(it.id,vm.state.orgUnits)}.ifBlank{if(item.roleCode=="USER")"本人所在班级" else "系统账户管理"})};IconButton({revoke=item},enabled=!vm.state.busy&&item.status=="ACTIVE"){Icon(Icons.Outlined.RemoveModerator,"取消${UiText.label(item.roleCode)}")}}}
                ChoiceField("添加身份",role,Contract.ROLES.map{it to UiText.label(it)}){role=it;scopes=emptySet()}
                ScopeChoices(vm.state.orgUnits,role,scopes){scopes=it};Field("操作原因",reason){reason=it}
                Button({vm.addAssignment(account,role,scopes.toList(),reason)},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!vm.state.busy&&reason.length>=2&&(!listOf("TEACHER_ADMIN","STUDENT_ADMIN","MEDICAL_INSURANCE_ADMIN").contains(role)||scopes.isNotEmpty())&&!vm.state.assignments.any{it.roleCode==role&&it.status=="ACTIVE"}){Text(if(role=="SYSTEM_ADMIN")"提交管理员复核" else "添加身份")}
            }
        }
    }
    revoke?.let{item->AlertDialog({revoke=null},title={Text("取消身份？")},text={Text("取消“${UiText.label(item.roleCode)}”后，该账户需要重新登录。")},confirmButton={Button({vm.revokeAssignment(item.id,reason,account);revoke=null},enabled=!vm.state.busy&&reason.length>=2){Text("确认取消")}},dismissButton={TextButton({revoke=null}){Text("返回")}})}
}
@Composable private fun ControlList(vm:ShtongViewModel,initialReason:String){
    var reason by remember{mutableStateOf(initialReason)};var pending by remember{mutableStateOf<kotlinx.serialization.json.JsonObject?>(null)};var approve by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{Field("复核意见",reason){reason=it}}
        items(vm.state.controls){control->
            val status=control["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Column(Modifier.fillMaxWidth().padding(8.dp)){Text(UiText.label(control["commandType"]?.jsonPrimitive?.contentOrNull.orEmpty()),fontWeight=FontWeight.Bold);Text(control["reason"]?.jsonPrimitive?.contentOrNull.orEmpty());Text(UiText.label(status));Text("涉及人数："+(control["targetCount"]?.jsonPrimitive?.contentOrNull?:"未记录"));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({pending=control;approve=false},enabled=!vm.state.busy&&status=="PENDING"&&reason.length>=2){Text("拒绝")};Button({pending=control;approve=true},enabled=!vm.state.busy&&status=="PENDING"&&reason.length>=2){Text("同意")}}}
        }
    }
    pending?.let{control->AlertDialog({pending=null},title={Text(if(approve)"同意并执行申请？" else "拒绝申请？")},text={Text(UiText.label(control["commandType"]?.jsonPrimitive?.contentOrNull.orEmpty())+"："+control["reason"]?.jsonPrimitive?.contentOrNull.orEmpty())},confirmButton={Button({val id=control["id"]?.jsonPrimitive?.contentOrNull.orEmpty();val version=control["version"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?:1;vm.decideControl(id,version,approve,reason);pending=null},enabled=!vm.state.busy){Text("确认")}},dismissButton={TextButton({pending=null}){Text("取消")}})}
}

@Composable private fun OrganizationScreen(vm:ShtongViewModel){
    var type by remember{mutableStateOf("SCHOOL")};var parent by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var reason by remember{mutableStateOf("维护学校与班级")};var editing by remember{mutableStateOf<OrgUnit?>(null)};var editName by remember{mutableStateOf("")};var deleteTarget by remember{mutableStateOf<OrgUnit?>(null)}
    val parentType=mapOf("COLLEGE" to "SCHOOL","GRADE" to "COLLEGE","CLASS" to "GRADE")[type]
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{CommandFeedback(vm.state)}
        item{ChoiceField("添加类型",type,listOf("SCHOOL","COLLEGE","GRADE","CLASS").map{it to UiText.label(it)}){type=it;parent=""}}
        if(parentType!=null)item{ChoiceField("所属${UiText.label(parentType)}",parent,vm.state.orgUnits.filter{it.type==parentType&&it.status=="ACTIVE"}.map{it.id to orgName(it.id,vm.state.orgUnits)}){parent=it}}
        item{Field("${UiText.label(type)}名称",name){name=it}}
        item{Field("组织编号（留空自动生成）",code){code=it}}
        item{Field("操作原因",reason){reason=it}}
        item{Button({vm.createOrg(type,if(type=="SCHOOL")null else parent,code,name,reason){name="";code=""}},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!vm.state.busy&&name.isNotBlank()&&reason.length>=2&&(type=="SCHOOL"||parent.isNotBlank())){Text(if(vm.state.busy)"正在创建" else "创建${UiText.label(type)}")}}
        items(vm.state.orgUnits,key={it.id}){unit->ListItem(headlineContent={Text(orgName(unit.id,vm.state.orgUnits))},supportingContent={Text("${UiText.label(unit.type)} · ${unit.code}")},trailingContent={Row{IconButton({vm.clearFeedback();editing=unit;editName=unit.name},enabled=!vm.state.busy){Icon(Icons.Outlined.Edit,"修改${unit.name}名称")};IconButton({deleteTarget=unit},enabled=!vm.state.busy){Icon(Icons.Outlined.Delete,"删除${unit.name}")}}})}
    }
    editing?.let{unit->AlertDialog({if(!vm.state.busy)editing=null},title={Text("修改名称")},text={Column{Field("名称",editName){editName=it};ErrorText(vm.state.error)}},confirmButton={Button({vm.updateOrg(unit,editName,reason){editing=null}},enabled=!vm.state.busy&&editName.isNotBlank()){Text("保存")}},dismissButton={TextButton({editing=null},enabled=!vm.state.busy){Text("取消")}})}
    deleteTarget?.let{unit->AlertDialog({deleteTarget=null},title={Text("确认删除组织")},text={Text("删除“${orgName(unit.id,vm.state.orgUnits)}”？有下级组织、在用账户或权限时无法删除。删除后可重新使用原名称和编号创建。")},confirmButton={Button({vm.deleteOrg(unit.id,reason);deleteTarget=null},enabled=!vm.state.busy){Text("确认删除")}},dismissButton={TextButton({deleteTarget=null}){Text("取消")}})}
}
@Composable private fun AuditScreen(vm:ShtongViewModel){
    val context=LocalContext.current;var code by remember{mutableStateOf("")};var reason by remember{mutableStateOf("安全审计核查")}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->val bytes=vm.state.exportBytes;if(uri!=null&&bytes!=null){runCatching{val stream=context.contentResolver.openOutputStream(uri)?:error("保存失败");stream.use{it.write(bytes)}}.onSuccess{vm.exportSaved()}.onFailure{vm.reportError("文件未保存，请选择可写入的位置重试")}}}
    Column(Modifier.fillMaxSize().padding(12.dp)){CommandFeedback(vm.state);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Box(Modifier.weight(1f)){ChoiceField("操作类型",code,listOf("" to "全部操作")+UiText.events.map{it to UiText.label(it)}){code=it}};IconButton({vm.loadAudit(code)},Modifier.size(48.dp)){Icon(Icons.Outlined.Search,"查询")};IconButton({vm.requestAuditExport(reason,code)},Modifier.size(48.dp)){Icon(Icons.Outlined.Download,"申请导出审计")}};OutlinedTextField(reason,{reason=it},Modifier.fillMaxWidth(),label={Text("查询或导出用途")});if(vm.state.auditExportJobId!=null)OutlinedButton(vm::refreshAuditExport,Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.Refresh,null);Spacer(Modifier.width(6.dp));Text("刷新并领取审计导出")};if(vm.state.exportBytes!=null)Button({saver.launch(vm.state.exportFileName)},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("保存审计导出")};LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)){items(vm.state.audits,key={it.eventId}){e->ListItem(headlineContent={Text(UiText.label(e.eventCode),fontWeight=FontWeight.Bold)},supportingContent={Text("${UiText.label(e.outcome)} · ${e.occurredAt}")},modifier=Modifier.clickable{vm.loadAuditDetail(e.eventId)})}}}
    vm.state.auditDetail?.let{e->AuditDetailDialog(e,vm::clearAuditDetail)}
}
@Composable private fun AuditDetailDialog(event:AuditEventDetail,close:()->Unit){
    var technical by remember(event.eventId){mutableStateOf(false)}
    AlertDialog(close,title={Text(UiText.label(event.eventCode))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("发生时间：${event.occurredAt}\n结果：${UiText.label(event.outcome)}\n权限校验：${UiText.label(event.authorizationDecision)}\n涉及人数：${event.subjectCount}\n工作区：${UiText.label(event.workspace?:"未选择")}\n渠道：${UiText.label(event.channel?:"未记录")}")
        TextButton({technical=!technical},Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(technical)"收起技术排障明细" else "展开技术排障明细")}
        if(technical){
            Text("请求编号：${event.requestId}\n操作人编号：${event.actorAccountId?:"系统或未登录人员"}\n涉及人员编号：${event.subjects.joinToString("、").ifBlank{"无"}}")
            listOf("对象" to event.resources,"内容变化" to event.changes,"权限校验依据" to event.authorizationEvidence).forEach{(title,values)->
                Text(title,fontWeight=FontWeight.Bold)
                Text(values.joinToString("\n\n"){renderAuditValue(JsonObject(it))}.ifBlank{"无"})
            }
        }
    }},confirmButton={TextButton(close){Text("关闭")}})
}
private fun renderAuditValue(value:JsonElement):String=when(value){
    JsonNull->"无"
    is JsonObject->value.entries.joinToString("\n"){(key,item)->"${UiText.label(key)}：${renderAuditValue(item)}"}
    is JsonArray->value.joinToString("；"){renderAuditValue(it)}
    is JsonPrimitive->when(value.content){"true"->"是";"false"->"否";else->UiText.label(value.content)}
}
private data class SensitiveCopyRequest(val field:String,val label:String)
@Composable private fun ProfileScreen(vm:ShtongViewModel){
    val profile=vm.state.profile;val context=LocalContext.current;var sensitive by remember{mutableStateOf<SensitiveCopyRequest?>(null)};var password by remember{mutableStateOf("")}
    fun copy(label:String,value:String){if(value.isBlank()||value=="—")return;val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;clipboard.setPrimaryClip(ClipData.newPlainText(label,value));vm.clearFeedback()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        CommandFeedback(vm.state);Text("账户资料",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("点击任一资料即可复制；敏感资料需先验证密码。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        CopyProfileLine("姓名",profile?.displayName?:"—"){copy("姓名",profile?.displayName.orEmpty())}
        CopyProfileLine("学号/工号",profile?.personNo?:"—"){copy("学号/工号",profile?.personNo.orEmpty())}
        val roles=profile?.roleCodes?.joinToString("、"){UiText.label(it)}?:"—";CopyProfileLine("身份",roles){copy("身份",roles)}
        if(profile?.roleCodes?.contains("USER")==true){
            val gender=UiText.label(profile.gender?:"未填写");CopyProfileLine("性别",gender){copy("性别",gender)};CopyProfileLine("民族",profile.ethnicity?:"未填写"){copy("民族",profile.ethnicity?:"未填写")};ProfileLine("年龄",profile.age?.toString() ?: "—")
            profile.organizationPath.forEach{item->CopyProfileLine(UiText.label(item.type),item.name){copy(UiText.label(item.type),item.name)}}
            val counselors=profile.counselors.joinToString("、"){"${it.displayName}（${it.personNo}）"}.ifBlank{"暂未匹配"};CopyProfileLine("对应辅导员",counselors){copy("对应辅导员",counselors)}
            CopyProfileLine("身份证号",profile.idCardNumberMasked?:"—",true){sensitive=SensitiveCopyRequest("ID_CARD_NUMBER","身份证号")}
            CopyProfileLine("手机号",profile.mobileNumberMasked?:"—",true){sensitive=SensitiveCopyRequest("MOBILE_NUMBER","手机号")}
            CopyProfileLine("现家庭住址",profile.currentAddressMasked?:"—",true){sensitive=SensitiveCopyRequest("CURRENT_ADDRESS","现家庭住址")}
            CopyProfileLine("户籍地址",profile.householdAddressMasked?:"—",true){sensitive=SensitiveCopyRequest("HOUSEHOLD_ADDRESS","户籍地址")}
        }
        profile?.managedScopes.orEmpty().forEach{scope->val value=scope.scopes.joinToString("、"){it.path}.ifBlank{"—"};CopyProfileLine("${UiText.label(scope.roleCode)}管辖范围",value){copy("管辖范围",value)}}
        Button({vm.navigate(Route.PASSWORD)},Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(Icons.Outlined.Key,null);Spacer(Modifier.width(6.dp));Text("修改密码")}
        Button(vm::logout,Modifier.fillMaxWidth().heightIn(min=48.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.AutoMirrored.Outlined.Logout,null);Spacer(Modifier.width(6.dp));Text("退出登录")}
    }
    sensitive?.let{request->AlertDialog(onDismissRequest={if(!vm.state.busy){sensitive=null;password=""}},title={Text("验证后复制${request.label}")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("为保护个人信息，请输入当前登录密码。内容只会复制到剪贴板，不会在页面显示。");PasswordField("当前密码",password){password=it};ErrorText(vm.state.error)}},confirmButton={Button({vm.revealSensitiveProfileField(request.field,password){value->copy(request.label,value);sensitive=null;password=""}},enabled=!vm.state.busy&&password.isNotBlank()){Text(if(vm.state.busy)"正在验证" else "验证并复制")}},dismissButton={TextButton({sensitive=null;password=""},enabled=!vm.state.busy){Text("取消")}})}
}
@Composable private fun AccountPasswordScreen(vm:ShtongViewModel){var current by remember{mutableStateOf("")};var next by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};val policy=meetsPasswordPolicy(next);Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){CommandFeedback(vm.state);TextButton({vm.navigate(Route.PROFILE)}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(5.dp));Text("返回个人中心")};Text("修改密码",fontSize=22.sp,fontWeight=FontWeight.Bold);PasswordField("当前密码",current){current=it};PasswordField("新密码（至少 8 位，含大小写和数字）",next){next=it};PasswordField("确认新密码",confirm){confirm=it};Button({when{next!=confirm->vm.reportError("两次输入的新密码不一致");!policy->vm.reportError("密码至少 8 位，并同时包含大写字母、小写字母和数字");else->vm.changeProfilePassword(current,next)}},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!vm.state.busy&&current.isNotBlank()&&confirm.isNotBlank()){Text(if(vm.state.busy)"正在修改" else "确认修改")}}}
@Composable private fun NewAccountPasswordDialog(password:String,close:()->Unit){val context=LocalContext.current;var copied by remember(password){mutableStateOf(false)};AlertDialog(onDismissRequest=close,title={Text("账户已创建")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("初始密码仅显示一次，请复制后妥善交给本人。");SelectionContainer{Text(password,fontWeight=FontWeight.Bold)};if(copied)Text("已复制",color=MaterialTheme.colorScheme.primary)}},confirmButton={Button({val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;clipboard.setPrimaryClip(ClipData.newPlainText("生化通初始密码",password));copied=true},Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.ContentCopy,null);Spacer(Modifier.width(6.dp));Text("复制密码")}},dismissButton={TextButton(close,Modifier.heightIn(min=48.dp)){Text("关闭")}})}
@Composable private fun ProfileLine(label:String,value:String){ListItem(headlineContent={Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant)},supportingContent={Text(value)},modifier=Modifier.fillMaxWidth())}
@Composable private fun CopyProfileLine(label:String,value:String,sensitive:Boolean=false,onCopy:()->Unit){ListItem(headlineContent={Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant)},supportingContent={Text(value)},trailingContent={Icon(if(sensitive)Icons.Outlined.Shield else Icons.Outlined.ContentCopy,if(sensitive)"验证并复制" else "复制")},modifier=Modifier.fillMaxWidth().clickable(onClick=onCopy))}
@Composable private fun AccountDetailDialog(vm:ShtongViewModel,detail:AccountDetail){
    var edit by remember{mutableStateOf(false)};var enrollment by remember{mutableStateOf(false)};var login by remember(detail){mutableStateOf(detail.loginName)};var name by remember(detail){mutableStateOf(detail.displayName)};var gender by remember(detail){mutableStateOf(detail.gender?:"")};var classId by remember(detail){mutableStateOf(detail.classId.orEmpty())};var reason by remember{mutableStateOf("账户资料维护")}
    var idCard by remember(detail){mutableStateOf(detail.idCardNumber.orEmpty())};var mobile by remember(detail){mutableStateOf(detail.mobileNumber.orEmpty())}
    var currentProvince by remember(detail){mutableStateOf(detail.currentAddress?.provinceCode.orEmpty())};var currentCity by remember(detail){mutableStateOf(detail.currentAddress?.cityCode.orEmpty())};var currentDistrict by remember(detail){mutableStateOf(detail.currentAddress?.districtCode.orEmpty())};var currentDetail by remember(detail){mutableStateOf(detail.currentAddress?.detail.orEmpty())}
    var householdProvince by remember(detail){mutableStateOf(detail.householdAddress?.provinceCode.orEmpty())};var householdCity by remember(detail){mutableStateOf(detail.householdAddress?.cityCode.orEmpty())};var householdDistrict by remember(detail){mutableStateOf(detail.householdAddress?.districtCode.orEmpty())};var householdDetail by remember(detail){mutableStateOf(detail.householdAddress?.detail.orEmpty())}
    LaunchedEffect(edit){if(edit&&detail.profileCompleted)vm.loadAccountAddressOptions(detail)}
    val sensitiveReady=!detail.profileCompleted||(idCard.matches(Regex("^[0-9]{17}[0-9Xx]$"))&&mobile.matches(Regex("^1[3-9][0-9]{9}$"))&&listOf(currentProvince,currentCity,currentDistrict,householdProvince,householdCity,householdDistrict).all(String::isNotBlank)&&currentDetail.length>=2&&householdDetail.length>=2)
    AlertDialog(onDismissRequest={if(!vm.state.busy)vm.clearAccountDetail()},title={Text(detail.displayName)},text={
        if(edit){Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Field("姓名",name){name=it};ProfileLine("学号/工号（不可修改）",detail.personNo);Field("登录账号",login){login=it}
            if(detail.roleCodes.contains("USER"))ChoiceField("性别（选填）",gender,listOf("" to "未填写","MALE" to "男","FEMALE" to "女","OTHER" to "其他")){gender=it}
            if(detail.roleCodes.contains("USER")&&detail.profileCompleted){Field("身份证号",idCard){idCard=it.take(18)};Field("手机号",mobile){mobile=it.filter(Char::isDigit).take(11)};ProfileAddressFields("现家庭住址",vm.state.provinces,vm.state.currentCities,vm.state.currentDistricts,currentProvince,currentCity,currentDistrict,currentDetail,{currentProvince=it;currentCity="";currentDistrict="";vm.selectProfileProvince("current",it)},{currentCity=it;currentDistrict="";vm.selectProfileCity("current",it)},{currentDistrict=it},{currentDetail=it});ProfileAddressFields("户籍地址",vm.state.provinces,vm.state.householdCities,vm.state.householdDistricts,householdProvince,householdCity,householdDistrict,householdDetail,{householdProvince=it;householdCity="";householdDistrict="";vm.selectProfileProvince("household",it)},{householdCity=it;householdDistrict="";vm.selectProfileCity("household",it)},{householdDistrict=it},{householdDetail=it})}
            else if(detail.roleCodes.contains("USER"))Text("身份证号、手机号和地址尚未由本人完成首次填写，管理员暂不能代填。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Field("更正原因",reason){reason=it}
        }}else if(enrollment){Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("学生：${detail.displayName}（${detail.personNo}）");ProfileLine("当前班级",detail.organizationPath.joinToString(" / "){it.name}.ifBlank{"未设置"});ChoiceField("转入班级",classId,vm.state.orgUnits.filter{it.type=="CLASS"&&it.status=="ACTIVE"}.map{it.id to orgName(it.id,vm.state.orgUnits)}){classId=it};Field("变动原因",reason){reason=it}}}
        else{Column(Modifier.verticalScroll(rememberScrollState())){ProfileLine("登录账号",detail.loginName);ProfileLine("姓名",detail.displayName);ProfileLine("学号/工号",detail.personNo);ProfileLine("身份",detail.roleCodes.joinToString("、"){UiText.label(it)});if(detail.roleCodes.contains("USER")||detail.lifecycleStatus=="GRADUATED"){ProfileLine("性别",UiText.label(detail.gender?:"未填写"));ProfileLine("民族",detail.ethnicity?:"未填写");ProfileLine("年龄",detail.age?.toString()?:"未填写");detail.organizationPath.forEach{item->ProfileLine(UiText.label(item.type),item.name)};ProfileLine("身份证号",detail.idCardNumber?:"尚未由本人完善");ProfileLine("手机号",detail.mobileNumber?:"尚未由本人完善");ProfileLine("现家庭住址",detail.currentAddress?.let(::fullAddressDetail)?:"尚未由本人完善");ProfileLine("户籍地址",detail.householdAddress?.let(::fullAddressDetail)?:"尚未由本人完善")};if(detail.lifecycleStatus=="GRADUATED"){ProfileLine("毕业时间",detail.graduatedAt?:"历史档案");ProfileLine("毕业原因",detail.graduatedReason?:"历史毕业档案")};ProfileLine("使用状态",UiText.label(detail.lifecycleStatus));ProfileLine("安全状态",UiText.label(detail.securityStatus))}}
    },confirmButton={if(edit)Button({vm.updateAccount(login,name,gender.ifBlank{null},reason,idCard.takeIf{detail.profileCompleted},mobile.takeIf{detail.profileCompleted},ProfileAddressInput(currentProvince,currentCity,currentDistrict,currentDetail).takeIf{detail.profileCompleted},ProfileAddressInput(householdProvince,householdCity,householdDistrict,householdDetail).takeIf{detail.profileCompleted},onSuccess={edit=false})},enabled=!vm.state.busy&&login.isNotBlank()&&name.isNotBlank()&&reason.length>=2&&sensitiveReady){Text(if(vm.state.busy)"正在保存" else "保存更正")}else if(enrollment)Button({vm.changeEnrollment(classId,reason){enrollment=false}},enabled=!vm.state.busy&&classId.isNotBlank()&&classId!=detail.classId&&reason.length>=2){Text(if(vm.state.busy)"正在办理" else "确认学籍变动")}else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(detail.lifecycleStatus=="ACTIVE")TextButton({reason="更正账户资料";edit=true}){Text("更正资料")};if(detail.roleCodes.contains("USER")&&detail.lifecycleStatus=="ACTIVE")TextButton({reason="学生学籍变动";enrollment=true}){Text("学籍变动")}}},dismissButton={TextButton(vm::clearAccountDetail,enabled=!vm.state.busy){Text("关闭")}})
}
private fun fullAddressDetail(value:ProfileAddressDetail)=value.provinceName+value.cityName+value.districtName+value.detail

@Composable private fun Field(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true)}
@Composable private fun PasswordField(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,visualTransformation=PasswordVisualTransformation())}
@Composable private fun ErrorText(text:String?){if(text!=null)Text(text,color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Medium)}
@Composable private fun CommandFeedback(state:UiState){state.error?.let{Text(it,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer)};state.notice?.let{Text(it,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp),color=MaterialTheme.colorScheme.onPrimaryContainer)}}
@Composable private fun ActionButton(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){FilledTonalButton(onClick,Modifier.heightIn(min=48.dp)){Icon(icon,null);Spacer(Modifier.width(5.dp));Text(label)}}
private fun copyToCache(context:Context,uri:android.net.Uri):File?=runCatching {
    File(context.cacheDir,"account-import-${System.currentTimeMillis()}.xlsx").also { target ->
        context.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use(input::copyTo)
        }
    }
}.getOrNull()

import java.net.URI

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.plugin.serialization") }
val releaseApiBase=providers.gradleProperty("shtong.releaseApiBaseUrl").orNull.orEmpty()
val releaseDownloadHost=providers.gradleProperty("shtong.trustedDownloadHost").orNull.orEmpty()
val releaseAssociatedHost=providers.gradleProperty("shtong.associatedHost").orNull.orEmpty()
val mobPushAppKey=providers.gradleProperty("shtong.mobPushAppKey").orNull.orEmpty()
val privacyOperator=providers.gradleProperty("shtong.privacyOperator").orNull.orEmpty()
val privacyContact=providers.gradleProperty("shtong.privacyContact").orNull.orEmpty()
// MobTech push is optional: its proprietary binaries are not distributed with the source.
val mobPushLibs=listOf("MobPush-3.3.9.aar","MobCommons-2021.0601.1533.jar","MobTools-2021.0601.1530.jar").map{file("libs/$it")}
val mobPushBundled=mobPushLibs.all{it.isFile}
val debugApiBase=providers.gradleProperty("shtong.debugApiBaseUrl").getOrElse("http://10.0.2.2:18081/v1")
val debugApiUri=URI(debugApiBase)
require(debugApiUri.scheme in listOf("http","https") && debugApiUri.host!=null && debugApiUri.rawUserInfo==null && debugApiUri.rawQuery==null && debugApiUri.rawFragment==null && debugApiUri.path=="/v1") { "shtong.debugApiBaseUrl must be an HTTP(S) API URL ending in /v1 without credentials, query or fragment" }
android {
    namespace = "com.shtong.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.shtong.app"; minSdk = 26; targetSdk = 35; versionCode = 11; versionName = "0.1.10"; buildConfigField("String","MOB_PUSH_APP_KEY","\"$mobPushAppKey\"");buildConfigField("boolean","MOB_PUSH_BUNDLED","$mobPushBundled"); buildConfigField("String","PRIVACY_OPERATOR","\"$privacyOperator\""); buildConfigField("String","PRIVACY_CONTACT","\"$privacyContact\"") }
    buildTypes { debug { buildConfigField("String", "API_BASE_URL", "\"$debugApiBase\"");buildConfigField("String","TRUSTED_DOWNLOAD_HOST","\"\"");manifestPlaceholders["shtongAssociatedHost"]="debug.shtong.local";manifestPlaceholders["allowCleartext"]=true }; release { buildConfigField("String","API_BASE_URL","\"$releaseApiBase\"");buildConfigField("String","TRUSTED_DOWNLOAD_HOST","\"$releaseDownloadHost\"");manifestPlaceholders["shtongAssociatedHost"]=releaseAssociatedHost;manifestPlaceholders["allowCleartext"]=false;isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets["main"].java.srcDir(if(mobPushBundled)"src/mobpush/java" else "src/nopush/java")
}
val validateReleaseConfiguration=tasks.register("validateReleaseConfiguration") { doLast { require(releaseApiBase.startsWith("https://")&&!releaseApiBase.contains(".example")){"Set -Pshtong.releaseApiBaseUrl to the production HTTPS API URL"};require(!releaseDownloadHost.contains(".example")){"Set -Pshtong.trustedDownloadHost to the production artifact host or leave it empty for same-origin downloads"};require(releaseAssociatedHost.isNotBlank()&&!releaseAssociatedHost.contains(".example")){"Set -Pshtong.associatedHost to the verified Android App Link host"};require(mobPushAppKey.isNotBlank()&&!mobPushAppKey.contains(".example")){"Set -Pshtong.mobPushAppKey to the MobTech AppKey; never put AppSecret in the client"};require(privacyOperator.isNotBlank()){"Set -Pshtong.privacyOperator to the legal app operator name"};require(privacyContact.isNotBlank()){"Set -Pshtong.privacyContact to the privacy contact channel"} } }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseConfiguration) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    if(mobPushBundled)implementation(files(mobPushLibs))
    debugImplementation("androidx.compose.ui:ui-tooling")
}

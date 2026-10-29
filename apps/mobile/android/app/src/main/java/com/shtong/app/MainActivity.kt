package com.shtong.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity:ComponentActivity(){
    private var deepLink by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        deepLink=intent?.data
        val privacyStore=PrivacyConsentStore(this)
        if(privacyStore.isAccepted())MobPushConsentController.startAfterConsent(this)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.auto(0xFFF4F7F5.toInt(),0xFF111917.toInt()),navigationBarStyle=SystemBarStyle.auto(0xFFF4F7F5.toInt(),0xFF111917.toInt()))
        setContent{ShtongTheme(isSystemInDarkTheme()){
            val vm:ShtongViewModel=viewModel();var obscured by remember{mutableStateOf(false)};var privacyDecided by remember{mutableStateOf(privacyStore.isDecided())};
            val owner=LocalLifecycleOwner.current
            val notificationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ }
            LaunchedEffect(privacyDecided){
                if(privacyStore.isAccepted()&&notificationPermissionNeeded(this@MainActivity))notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            LaunchedEffect(deepLink){vm.handleDeepLink(deepLink)}
            DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->obscured=event==Lifecycle.Event.ON_STOP};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
            BackHandler(vm.state.phase==Phase.APP&&vm.state.route!=Route.MEDICAL_INSURANCE){val root=Route.HOME;if(vm.state.route==Route.PASSWORD)vm.navigate(Route.PROFILE)else if(vm.state.route!=root)vm.navigate(root)else finish()}
            ShtongApp(vm,obscured)
            PrivacyPolicyGate(
                accepted=privacyDecided,
                onAccept={privacyStore.accept();privacyDecided=true;MobPushConsentController.startAfterConsent(this@MainActivity)},
                onReject={privacyStore.decline();privacyDecided=true},
            )
        }}
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);deepLink=intent.data}
}

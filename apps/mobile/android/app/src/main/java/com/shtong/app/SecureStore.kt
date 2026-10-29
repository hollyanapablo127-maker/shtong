package com.shtong.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenStore { fun save(access:String,refresh:String);fun access():String?;fun refresh():String?;fun clear() }

class SecureStore(context:Context):TokenStore {
    private val prefs=context.getSharedPreferences("secure_session",Context.MODE_PRIVATE)
    private val alias="shtong.session.v1"
    private fun key():SecretKey { val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val existing=store.getKey(alias,null) as? SecretKey;if(existing!=null)return existing;return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey() }
    override fun save(access:String,refresh:String){prefs.edit{putString("access",encrypt(access));putString("refresh",encrypt(refresh))}}
    override fun access():String?=prefs.getString("access",null)?.let(::decrypt)
    override fun refresh():String?=prefs.getString("refresh",null)?.let(::decrypt)
    override fun clear(){prefs.edit{clear()}}
    private fun encrypt(value:String):String{val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)}
    private fun decrypt(value:String):String?=runCatching{val bytes=Base64.decode(value,Base64.NO_WRAP);val iv=bytes.copyOfRange(0,12);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv));String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)))}.getOrNull()
}

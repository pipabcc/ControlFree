package com.example.controlfree

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class CallStateMonitor(
    private val context: Context,
    private val onCallActiveChanged: (Boolean) -> Unit
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val telecomManager = context.getSystemService(TelecomManager::class.java)

    private var modernCallback: ModernCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private var callbackGeneration = 0
    private var isStarted = false

    fun start(): Boolean {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = telephonyManager ?: return false
        val generation = ++callbackGeneration
        isStarted = true

        return try {
            onCallActiveChanged(telecomManager?.isInCall == true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = ModernCallback { state -> handleState(generation, state) }
                modernCallback = callback
                manager.registerTelephonyCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Android 12 以前的兼容回调")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleState(generation, state)
                    }
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            true
        } catch (_: RuntimeException) {
            isStarted = false
            callbackGeneration++
            modernCallback = null
            legacyListener = null
            false
        }
    }

    fun stop() {
        isStarted = false
        callbackGeneration++
        val manager = telephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback?.let(manager::unregisterTelephonyCallback)
                modernCallback = null
            } else {
                legacyListener?.let { listener ->
                    @Suppress("DEPRECATION")
                    manager.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
                legacyListener = null
            }
        } catch (_: RuntimeException) {
            modernCallback = null
            legacyListener = null
        }
    }

    fun isCallActiveNow(): Boolean {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            telecomManager?.isInCall == true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun handleState(generation: Int, state: Int) {
        if (!isStarted || generation != callbackGeneration) return
        onCallActiveChanged(
            state == TelephonyManager.CALL_STATE_RINGING ||
                state == TelephonyManager.CALL_STATE_OFFHOOK
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class ModernCallback(
        private val callback: (Int) -> Unit
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            callback(state)
        }
    }
}

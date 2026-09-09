package com.example.controlfree.ai

import android.content.Context
import android.content.SharedPreferences

/** 非敏感的 AI 宠物开关；API Key 由 AiApiKeyStore 单独加密保存。 */
internal class AiCompanionPreferences internal constructor(
    context: Context,
    preferencesName: String = PREFERENCES_NAME
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun isCompanionEnabled(): Boolean = try {
        preferences.getBoolean(KEY_COMPANION_ENABLED, true)
    } catch (_: RuntimeException) {
        true
    }

    fun setCompanionEnabled(enabled: Boolean): Boolean = try {
        preferences.edit().putBoolean(KEY_COMPANION_ENABLED, enabled).commit()
    } catch (_: RuntimeException) {
        false
    }

    fun isLockChatEnabled(): Boolean = try {
        preferences.getBoolean(KEY_LOCK_CHAT_ENABLED, true)
    } catch (_: RuntimeException) {
        true
    }

    fun setLockChatEnabled(enabled: Boolean): Boolean = try {
        preferences.edit().putBoolean(KEY_LOCK_CHAT_ENABLED, enabled).commit()
    } catch (_: RuntimeException) {
        false
    }

    fun getPetPersonality(): AiPersonality = try {
        val key = preferences.getString(KEY_PET_PERSONALITY, AiPersonality.GENTLE.key).orEmpty()
        AiPersonality.fromKey(key)
    } catch (_: RuntimeException) {
        AiPersonality.GENTLE
    }

    fun setPetPersonality(personality: AiPersonality): Boolean = try {
        preferences.edit().putString(KEY_PET_PERSONALITY, personality.key).commit()
    } catch (_: RuntimeException) {
        false
    }

    companion object {
        internal const val PREFERENCES_NAME = "control_free_ai_companion"
        private const val KEY_COMPANION_ENABLED = "companion_enabled"
        private const val KEY_LOCK_CHAT_ENABLED = "lock_chat_enabled"
        private const val KEY_PET_PERSONALITY = "pet_personality"

        @Volatile
        private var instance: AiCompanionPreferences? = null

        fun getInstance(context: Context): AiCompanionPreferences =
            instance ?: synchronized(this) {
                instance ?: AiCompanionPreferences(context.applicationContext).also {
                    instance = it
                }
            }
    }
}

package com.fyn.app.core

import android.content.Context
import android.content.SharedPreferences

class AliasesStore(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("aliases_prefs", Context.MODE_PRIVATE)

    // Canonical keys
    object Keys {
        const val FACEBOOK = "facebook"
        const val INSTAGRAM = "instagram"
        const val SNAPCHAT = "snapchat"
        const val LINKEDIN = "linkedin"
        const val X = "x"
        const val TIKTOK = "tiktok"
    }

    fun getAliases(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        listOf(
            Keys.FACEBOOK, Keys.INSTAGRAM, Keys.SNAPCHAT,
            Keys.LINKEDIN, Keys.X, Keys.TIKTOK
        ).forEach { k ->
            prefs.getString(k, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { m[k] = it }
        }
        return m
    }

    fun setAlias(key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, value.trim())
        }.apply()
    }
}

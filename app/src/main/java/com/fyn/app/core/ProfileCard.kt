package com.fyn.app.core

import org.json.JSONObject

data class ProfileCard(
    val version: Int = 1,
    val aliases: Map<String, String>, // e.g., mapOf("ig" to "@achraf", "sc" to "achraf123")
    val tags: List<String> = emptyList(),
    val expEpochSec: Long? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("v", version)
        val a = JSONObject()
        aliases.forEach { (k,v) -> a.put(k, v) }
        o.put("aliases", a)
        o.put("tags", tags)
        expEpochSec?.let { o.put("exp", it) }
        return o.toString()
    }

    companion object {
        fun fromJson(json: String): ProfileCard {
            val o = JSONObject(json)
            val v = o.optInt("v", 1)
            val aJson = o.getJSONObject("aliases")
            val aliases = mutableMapOf<String,String>()
            aJson.keys().forEach { key -> aliases[key] = aJson.getString(key) }
            val tags = mutableListOf<String>()
            o.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) tags += arr.getString(i)
            }
            val exp = if (o.has("exp")) o.getLong("exp") else null
            return ProfileCard(v, aliases, tags, exp)
        }
    }
}

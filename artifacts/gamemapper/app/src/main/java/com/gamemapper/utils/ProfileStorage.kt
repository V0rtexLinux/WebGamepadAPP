package com.gamemapper.utils

import android.content.Context
import com.gamemapper.models.ControlProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ProfileStorage {
    private val gson = Gson()

    fun saveProfile(context: Context, profile: ControlProfile) {
        val profiles = loadProfiles(context).toMutableList()
        profiles.removeAll { it.id == profile.id }
        profiles.add(0, profile)
        val json = gson.toJson(profiles)
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(Constants.KEY_PROFILES, json).apply()
    }

    fun loadProfiles(context: Context): List<ControlProfile> {
        val json = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.KEY_PROFILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ControlProfile>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteProfile(context: Context, profileId: String) {
        val profiles = loadProfiles(context).filter { it.id != profileId }
        val json = gson.toJson(profiles)
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(Constants.KEY_PROFILES, json).apply()
    }

    fun getProfile(context: Context, profileId: String): ControlProfile? {
        return loadProfiles(context).firstOrNull { it.id == profileId }
    }
}

package com.gamemapper.utils

object Constants {
    const val PREFS_NAME = "gamemapper_prefs"
    const val KEY_PROFILES = "saved_profiles"
    const val KEY_LAST_URL = "last_url"
    const val KEY_CREDENTIALS = "saved_credentials"
    const val KEY_GAMEPAD_CONFIG = "gamepad_config"
    const val KEY_AUTO_FARM_ENABLED = "auto_farm_enabled"
    const val KEY_FARM_STATS = "farm_stats"
    const val KEY_FARM_HISTORY = "farm_history"

    const val EXTRA_GAME_URL = "extra_game_url"
    const val EXTRA_PROFILE_ID = "extra_profile_id"
    const val EXTRA_ANALYSIS_MODE = "extra_analysis_mode"
    const val EXTRA_SOURCE_PROFILE_ID = "extra_source_profile_id"
    const val EXTRA_AUTO_FARM = "extra_auto_farm"

    const val ANALYSIS_MODE_DEEP = 0
    const val ANALYSIS_MODE_QUICK = 1
    const val ANALYSIS_MODE_REMAP = 2

    val CPPS_SHORTCUTS = listOf(
        "CPJourney"  to "https://play.cpjourney.net",
        "CPPS.app"   to "https://cpps.app/auth/login",
        "Icer"       to "https://icer.ink",
        "CP Legacy"  to "https://play.cplegacy.com"
    )

    // Farm broadcast actions (for in-app communication)
    const val ACTION_FARM_STATUS = "com.gamemapper.FARM_STATUS"
    const val ACTION_FARM_COIN_UPDATE = "com.gamemapper.COIN_UPDATE"
}

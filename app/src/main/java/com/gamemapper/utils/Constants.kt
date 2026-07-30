package com.gamemapper.utils

object Constants {
    const val PREFS_NAME = "gamemapper_prefs"
    const val KEY_PROFILES = "saved_profiles"
    const val KEY_LAST_URL = "last_url"
    const val EXTRA_GAME_URL = "extra_game_url"
    const val EXTRA_PROFILE_ID = "extra_profile_id"
    const val EXTRA_ANALYSIS_MODE = "extra_analysis_mode"
    const val EXTRA_SOURCE_PROFILE_ID = "extra_source_profile_id"

    const val ANALYSIS_MODE_DEEP = 0      // full analysis
    const val ANALYSIS_MODE_QUICK = 1     // quick re-scan
    const val ANALYSIS_MODE_REMAP = 2     // alternative layout

    // Known CPPS quick-fill URLs
    val CPPS_SHORTCUTS = listOf(
        "CPJourney"     to "https://play.cpjourney.net",
        "CPPS.app"      to "https://cpps.app/auth/login",
        "Icer"          to "https://icer.ink",
        "CP Legacy"     to "https://cplegacy.com"
    )
}

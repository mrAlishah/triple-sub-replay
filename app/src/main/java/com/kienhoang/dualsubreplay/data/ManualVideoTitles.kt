package com.kienhoang.dualsubreplay.data

import android.content.SharedPreferences

/** Titles the user typed for videos, by video id; kept apart from the words so a title exists before any word is saved. */
internal class ManualVideoTitles(
    private val preferences: SharedPreferences,
) {
    fun all(): Map<String, String> =
        preferences.all.entries
            .mapNotNull { (videoId, value) -> normalizeSourceTitle(value as? String)?.let { videoId to it } }
            .toMap()

    fun set(
        videoId: String,
        title: String,
    ) {
        preferences.edit().putString(videoId, title).apply()
    }
}

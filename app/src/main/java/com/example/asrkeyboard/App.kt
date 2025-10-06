package com.example.asrkeyboard

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Opt-in to Material You dynamic color for all activities when available (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}


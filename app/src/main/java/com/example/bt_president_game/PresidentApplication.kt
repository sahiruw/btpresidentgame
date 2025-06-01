package com.example.bt_president_game

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PresidentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

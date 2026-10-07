package com.circlecounter.app

import android.app.Application
import com.circlecounter.app.data.TrackRepository

class CircleCounterApp : Application() {
    lateinit var trackRepository: TrackRepository
        private set

    override fun onCreate() {
        super.onCreate()
        trackRepository = TrackRepository(this)
    }
}

package com.maxfluency.app

import android.app.Application
import androidx.work.Configuration

class MaxFluencyApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

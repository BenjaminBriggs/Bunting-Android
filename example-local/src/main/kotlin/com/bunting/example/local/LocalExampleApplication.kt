package com.bunting.example.local

import android.app.Application
import com.bunting.Bunting
import com.bunting.android.configure
import com.bunting.model.BuntingEnvironment

class LocalExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No bundled seed — this app exists to exercise a live local admin.
        Bunting.configure(
            this,
            BuntingEnvironment.DEVELOPMENT,
            seedAssetName = null,
        )
    }
}

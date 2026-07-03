package com.bunting.example

import android.app.Application
import com.bunting.Bunting
import com.bunting.android.configure
import com.bunting.model.BuntingEnvironment

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Bootstrap from assets/bunting_config.json, seed from assets/BuntingConfig.json.
        Bunting.configure(this, BuntingEnvironment.DEVELOPMENT)
    }
}

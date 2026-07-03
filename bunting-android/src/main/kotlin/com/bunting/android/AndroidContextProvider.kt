package com.bunting.android

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.bunting.evaluation.ContextProvider
import com.bunting.evaluation.DeviceDerivation
import com.bunting.evaluation.EvaluationContext
import java.util.Locale

/**
 * The Android [ContextProvider]. Reads the raw platform primitives off
 * `Build`/`Configuration`/`UiModeManager`/`Locale` and delegates every
 * contract-sensitive derivation to the JVM-tested [DeviceDerivation], so the
 * device-class/os-version/manufacturer rules are proven headlessly in `:bunting`.
 */
class AndroidContextProvider(context: Context) : ContextProvider {
    private val appContext = context.applicationContext

    override fun current(): EvaluationContext {
        val configuration = appContext.resources.configuration
        val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTelevision = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val deviceClass = DeviceDerivation.deviceClass(configuration.smallestScreenWidthDp, isTelevision)

        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        val buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

        val locale = Locale.getDefault()
        val country = locale.country
        val region = if (country.isEmpty()) null else country.uppercase(Locale.ROOT)

        return EvaluationContext(
            platform = "android",
            osVersion = DeviceDerivation.normalizeOsVersion(Build.VERSION.RELEASE ?: ""),
            appVersion = packageInfo.versionName ?: "",
            buildNumber = buildNumber.toString(),
            deviceModel = Build.MODEL ?: "",
            region = region,
            language = locale.language.lowercase(Locale.ROOT),
            deviceClass = deviceClass,
            reservedAttributes = mapOf("manufacturer" to DeviceDerivation.normalizeManufacturer(Build.MANUFACTURER ?: "")),
        )
    }
}

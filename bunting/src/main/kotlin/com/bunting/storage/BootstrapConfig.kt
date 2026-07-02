package com.bunting.storage

import com.bunting.verify.PublicKeyInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Platform-neutral bootstrap document (the JSON rendering the Android artifact
 *  ships as `bunting_config.json`). */
@Serializable
data class BootstrapConfig(
    @SerialName("app_identifier") val appIdentifier: String,
    @SerialName("endpoint_url") val endpointUrl: String,
    @SerialName("public_keys") val publicKeys: List<PublicKeyInfo>,
    @SerialName("fetch_policy") val fetchPolicy: FetchPolicy,
)

@Serializable
data class FetchPolicy(
    @SerialName("min_interval_seconds") val minIntervalSeconds: Int,
    @SerialName("hard_ttl_days") val hardTtlDays: Int,
)

package com.bunting.model

import kotlinx.serialization.json.Json

/** The shared decoder for all Bunting artifacts. Tolerates unknown keys so the
 *  contract can gain fields without breaking older clients; strict otherwise. */
val BuntingJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

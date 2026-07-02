package com.bunting.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

/** A local override value. Persisted as tagged JSON (`kind` discriminator). */
@Serializable
sealed class OverrideValue {
    @Serializable data class BoolValue(val value: Boolean) : OverrideValue()
    @Serializable data class IntValue(val value: Long) : OverrideValue()
    @Serializable data class DoubleValue(val value: Double) : OverrideValue()
    @Serializable data class StringValue(val value: String) : OverrideValue()
    @Serializable data class DateValue(val epochMillis: Long) : OverrideValue()
}

/** Local flag overrides for debugging/testing, persisted to a JSON file. */
class OverridesStore(private val file: File) {
    private val json = Json { prettyPrint = false; classDiscriminator = "kind" }
    private val serializer = MapSerializer(serializer<String>(), OverrideValue.serializer())
    private val overrides: MutableMap<String, OverrideValue> = load()

    private fun load(): MutableMap<String, OverrideValue> {
        if (file.exists() == false) return mutableMapOf()
        return try {
            json.decodeFromString(serializer, file.readText()).toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val text = json.encodeToString(serializer, overrides)
        tmp.writeText(text)
        if (tmp.renameTo(file) == false) { file.writeText(text); tmp.delete() }
    }

    fun setOverride(key: String, value: OverrideValue?) {
        if (value == null) overrides.remove(key) else overrides[key] = value
        persist()
    }

    fun getOverride(key: String): OverrideValue? = overrides[key]
    fun clearOverride(key: String) { overrides.remove(key); persist() }
    fun clearAll() { overrides.clear(); persist() }
    fun getAllOverrides(): Map<String, OverrideValue> = overrides.toMap()
}

package com.bunting.storage

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

/** Minimal persistence seam. Plan 3 supplies a DataStore-backed implementation. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** File-backed [KeyValueStore] (a JSON string map with atomic writes). Pure-JVM so
 *  identity/overrides work headlessly in the CLI and tests. */
class FileKeyValueStore(private val file: File) : KeyValueStore {
    private val json = Json { prettyPrint = false }
    private val serializer = MapSerializer(serializer<String>(), serializer<String>())
    private val cache: MutableMap<String, String> = load()

    private fun load(): MutableMap<String, String> {
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
        val text = json.encodeToString(serializer, cache)
        tmp.writeText(text)
        if (tmp.renameTo(file) == false) { file.writeText(text); tmp.delete() }
    }

    override fun getString(key: String): String? = cache[key]
    override fun putString(key: String, value: String) { cache[key] = value; persist() }
    override fun remove(key: String) { cache.remove(key); persist() }
}

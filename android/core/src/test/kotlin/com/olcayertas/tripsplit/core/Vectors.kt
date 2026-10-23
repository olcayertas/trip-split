package com.olcayertas.tripsplit.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Vectors {
    private val dir = File(requireNotNull(System.getProperty("vectors.dir")) { "vectors.dir is not set" })

    fun load(name: String): JsonObject =
        Json.parseToJsonElement(File(dir, "$name.json").readText(Charsets.UTF_8)).jsonObject
}

fun JsonElement.str(): String = jsonPrimitive.content
fun JsonElement.long(): Long = jsonPrimitive.content.toLong()
fun JsonElement.objs(): List<JsonObject> = jsonArray.map { it.jsonObject }
fun JsonElement.strings(): List<String> = jsonArray.map { it.str() }
fun JsonObject.longs(): Map<String, Long> = mapValues { it.value.long() }


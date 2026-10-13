package com.omvcompanion.app.data.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Lenient accessors: OMV returns numbers sometimes as JSON numbers and sometimes as strings
 * (e.g. 64-bit byte counts), and booleans occasionally as 0/1.
 */

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

fun JsonObject.dbl(key: String): Double? = str(key)?.toDoubleOrNull()

fun JsonObject.lng(key: String): Long? = str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let {
    it.booleanOrNull ?: it.content.toDoubleOrNull()?.let { n -> n != 0.0 }
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

val JsonElement.objOrNull: JsonObject? get() = this as? JsonObject

package dev.aidirector.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Small DSL for hand-writing JSON Schema fragments. Keeps tool definitions readable. */
internal object Schemas {

    fun obj(
        required: List<String> = emptyList(),
        additionalProperties: Boolean = false,
        block: PropertiesBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        val props = PropertiesBuilder().apply(block).build()
        put("properties", props)
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
        put("additionalProperties", JsonPrimitive(additionalProperties))
    }

    class PropertiesBuilder {
        private val entries = mutableMapOf<String, JsonObject>()

        fun string(name: String, description: String, enum: List<String>? = null, maxLength: Int? = null) {
            entries[name] = buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive(description))
                if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
                if (maxLength != null) put("maxLength", JsonPrimitive(maxLength))
            }
        }

        fun integer(name: String, description: String, min: Int? = null, max: Int? = null) {
            entries[name] = buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive(description))
                if (min != null) put("minimum", JsonPrimitive(min))
                if (max != null) put("maximum", JsonPrimitive(max))
            }
        }

        fun number(name: String, description: String, min: Double? = null, max: Double? = null) {
            entries[name] = buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive(description))
                if (min != null) put("minimum", JsonPrimitive(min))
                if (max != null) put("maximum", JsonPrimitive(max))
            }
        }

        fun boolean(name: String, description: String) {
            entries[name] = buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive(description))
            }
        }

        fun build(): JsonObject = JsonObject(entries.toMap())
    }
}

package dev.aidirector.util

import kotlinx.serialization.json.Json

/** Shared JSON instance — lenient enough for upstream LLM quirks, strict enough to catch our bugs. */
val DirectorJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    prettyPrint = false
}

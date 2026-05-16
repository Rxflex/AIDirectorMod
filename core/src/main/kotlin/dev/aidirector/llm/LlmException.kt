package dev.aidirector.llm

/** Common parent for everything the LLM client can throw. */
sealed class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Auth/4xx that won't recover on retry (except 408/429 — those become RateLimit/Transient). */
class LlmClientException(val status: Int, message: String) : LlmException("HTTP $status: $message")

/** 408/429 — back off and retry. */
class LlmRateLimitException(val retryAfterSeconds: Long?, message: String) :
    LlmException("Rate limited${retryAfterSeconds?.let { ", retry after ${it}s" } ?: ""}: $message")

/** 5xx, IO timeout, connection failure. Retried automatically up to [LlmConfig.maxRetries]. */
class LlmTransientException(message: String, cause: Throwable? = null) : LlmException(message, cause)

/** Malformed/unexpected response body. Not retried. */
class LlmResponseException(message: String, cause: Throwable? = null) : LlmException(message, cause)

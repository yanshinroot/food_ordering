package com.foodorder.staff.core

/**
 * Normalized outcome of a network call. Every layer above the transport
 * (ViewModels, screens) reacts to this instead of raw exceptions, so no
 * stack trace or raw exception message ever reaches the UI.
 */
sealed class ApiError(open val userMessage: String) {
    data class Network(override val userMessage: String) : ApiError(userMessage)
    data class Timeout(override val userMessage: String) : ApiError(userMessage)
    data class Unauthorized(override val userMessage: String) : ApiError(userMessage)
    data class Forbidden(override val userMessage: String) : ApiError(userMessage)
    data class NotFound(override val userMessage: String) : ApiError(userMessage)
    data class RateLimited(override val userMessage: String) : ApiError(userMessage)
    data class Validation(override val userMessage: String) : ApiError(userMessage)
    data class StaleVersion(override val userMessage: String, val orderId: Int) : ApiError(userMessage)
    data class Conflict(override val userMessage: String) : ApiError(userMessage)
    data class ServerError(override val userMessage: String, val status: Int) : ApiError(userMessage)
    data class Malformed(override val userMessage: String) : ApiError(userMessage)

    /** True for errors where blindly retrying the exact same request is safe. */
    val isSafeToAutoRetry: Boolean
        get() = this is Network || this is Timeout || (this is ServerError && status >= 500)
}

class ApiException(val error: ApiError, cause: Throwable? = null) : Exception(error.userMessage, cause)

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()

    inline fun onSuccess(block: (T) -> Unit): ApiResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (ApiError) -> Unit): ApiResult<T> {
        if (this is Failure) block(error)
        return this
    }
}

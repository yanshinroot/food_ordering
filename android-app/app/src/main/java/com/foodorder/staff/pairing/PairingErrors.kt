package com.foodorder.staff.pairing

import com.foodorder.staff.core.ApiError

/**
 * Turns a raw [ApiError] from the pairing/claim call into the exact wording
 * Step 3 requires (distinct expired / already-used / invalid-code /
 * network messages) — pulled out as a pure function so the mapping is
 * unit-testable without a real HTTP round trip, and so no server slug
 * ("invalid_code") or stack trace can leak into the UI.
 */
fun describePairingError(error: ApiError): String = when (error) {
    is ApiError.NotFound -> "This pairing code isn't valid. Check the code and try again."
    is ApiError.Conflict -> when {
        error.userMessage.contains("already been used", ignoreCase = true) ->
            "This pairing code has already been used. Ask your manager for a new one."
        error.userMessage.contains("expired", ignoreCase = true) ->
            "This pairing code has expired. Ask your manager for a new one."
        else -> error.userMessage
    }
    is ApiError.RateLimited -> "Too many attempts. Wait a moment and try again."
    is ApiError.Validation -> "Enter the pairing code."
    is ApiError.Network -> "Can't reach the server. Check the server address and network connection."
    is ApiError.Timeout -> "The server didn't respond in time. Check the connection and try again."
    is ApiError.ServerError -> "The server hit a problem. Try again shortly."
    else -> "Pairing failed. Try again."
}

fun describeRoleMismatch(expectedRole: String, actualRole: String): String {
    val expectedLabel = expectedRole.replaceFirstChar(Char::uppercase)
    val actualLabel = actualRole.replaceFirstChar(Char::uppercase)
    return "This code is for the $actualLabel app. This device is running the $expectedLabel app — " +
        "ask your manager for a $expectedLabel pairing code instead."
}

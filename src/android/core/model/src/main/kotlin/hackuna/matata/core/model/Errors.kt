package hackuna.matata.core.model

/** Фиксированный список кодов ошибок моста (`call.reject`). Совпадает с StenoErrorCode в steno.ts. */
enum class ErrorCode { MODELS_MISSING, NO_PERMISSION, DECODE_FAILED, OUT_OF_MEMORY, CANCELLED, NO_SPACE, UNKNOWN }

class StenoException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Приводит любую ошибку к коду моста. */
fun Throwable.toErrorCode(): ErrorCode = when (this) {
    is StenoException -> code
    is OutOfMemoryError -> ErrorCode.OUT_OF_MEMORY
    is kotlinx.coroutines.CancellationException -> ErrorCode.CANCELLED
    else -> cause?.takeIf { it !== this }?.toErrorCode() ?: ErrorCode.UNKNOWN
}

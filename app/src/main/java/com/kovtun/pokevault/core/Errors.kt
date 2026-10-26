package com.kovtun.pokevault.core

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Errors that come from the network / server and are safe to show and retry. */
fun Throwable.isRemoteFailure(): Boolean =
    this is IOException || this is HttpException || this is SerializationException

fun Throwable.toUserMessage(): String = when (this) {
    is IOException -> "Нет соединения с сервером"
    is HttpException -> "Ошибка сервера (${code()})"
    is SerializationException -> "Не удалось разобрать ответ сервера"
    else -> message ?: "Неизвестная ошибка"
}

/**
 * Runs [block] and wraps only remote failures into Result.failure.
 * CancellationException and programming errors are rethrown.
 */
inline fun <T> remoteCall(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        if (e.isRemoteFailure()) Result.failure(e) else throw e
    }

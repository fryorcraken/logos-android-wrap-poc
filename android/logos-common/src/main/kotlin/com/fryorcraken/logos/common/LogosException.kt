package com.fryorcraken.logos.common

/**
 * Thrown when a native call into a Logos library returns a non-OK status
 * (the native side's `RET_ERR` / `RET_MISSING_CALLBACK`, or an event
 * delivered with a non-zero `callerRet`).
 */
public class LogosException(message: String, public val nativeCode: Int) : Exception(message)

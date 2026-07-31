package com.tensal.denden

internal fun String.codePointLength(): Int = codePointCount(0, length)

internal fun String.ellipsizeCodePoints(maxLength: Int): String =
    if (codePointLength() <= maxLength) this else substring(0, offsetByCodePoints(0, maxLength)) + "…"

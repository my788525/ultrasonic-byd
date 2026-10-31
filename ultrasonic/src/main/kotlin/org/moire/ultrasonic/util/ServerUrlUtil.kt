/*
 * ServerUrlUtil.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import java.net.MalformedURLException
import java.net.URL

/**
 * Normalizes and validates the server address entered by the user.
 *
 * Beside ordinary host names this also accepts IPv6 literals in both the
 * bracketed ( http://[2001:db8::1]:4533 ) and the bare form
 * ( 2001:db8::1 ), because typing the brackets on a car head unit is painful.
 *
 * A bare IPv6 address containing a non standard port has to be written in the
 * bracketed form, exactly like in every web browser: [2001:db8::1]:4533
 */
object ServerUrlUtil {

    private const val SCHEME_SEPARATOR = "://"
    private const val DEFAULT_SCHEME = "http://"

    /**
     * Brings the address in the form expected by the REST client:
     * always with a scheme, IPv6 literals bracketed and without a trailing slash.
     */
    fun normalize(rawInput: String?): String {
        val trimmed = rawInput?.trim().orEmpty()
        if (trimmed.isEmpty()) return trimmed

        val withScheme =
            if (trimmed.contains(SCHEME_SEPARATOR)) trimmed else DEFAULT_SCHEME + trimmed
        val authorityStart = withScheme.indexOf(SCHEME_SEPARATOR) + SCHEME_SEPARATOR.length
        val prefix = withScheme.substring(0, authorityStart)
        val remainder = withScheme.substring(authorityStart)
        if (remainder.isEmpty()) return trimmed

        val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) remainder else remainder.substring(0, authorityEnd)
        val tail = if (authorityEnd < 0) "" else remainder.substring(authorityEnd)
        if (authority.isEmpty()) return trimmed

        return (prefix + bracketIpv6Literal(authority) + tail).trimEnd('/')
    }

    /**
     * Checks whether the given address can be used to reach a server.
     */
    fun isValid(rawInput: String?): Boolean {
        val normalized = normalize(rawInput)
        if (normalized.isEmpty() || !normalized.contains(SCHEME_SEPARATOR)) return false

        return try {
            val url = URL(normalized)
            !url.host.isNullOrBlank()
        } catch (exception: MalformedURLException) {
            false
        }
    }

    /**
     * The host part of the address, without the brackets of an IPv6 literal.
     * Used to pre-fill the name of a new server setting.
     */
    fun hostForDisplay(rawInput: String?): String? {
        val normalized = normalize(rawInput)
        if (normalized.isEmpty()) return null

        return try {
            URL(normalized).host?.removeSurrounding("[", "]")
        } catch (exception: MalformedURLException) {
            null
        }
    }

    /**
     * Wraps a bare IPv6 literal in square brackets, as required by RFC 3986.
     * An authority with a single colon is treated as host:port and left alone.
     */
    private fun bracketIpv6Literal(authority: String): String {
        if (authority.startsWith("[")) return authority
        if (!looksLikeIpv6Literal(authority)) return authority

        return "[$authority]"
    }

    /**
     * An IPv6 literal consists only of hexadecimal digits, dots (embedded IPv4)
     * and at least two colons. A host name or a host:port pair never matches.
     */
    private fun looksLikeIpv6Literal(authority: String): Boolean {
        if (authority.count { it == ':' } < 2) return false

        return authority.all { character ->
            character.isDigit() ||
                character in 'a'..'f' ||
                character in 'A'..'F' ||
                character == ':' ||
                character == '.'
        }
    }
}

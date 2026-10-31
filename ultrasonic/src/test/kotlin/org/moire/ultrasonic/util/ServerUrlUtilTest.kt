/*
 * ServerUrlUtilTest.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import org.amshove.kluent.`should be equal to`
import org.amshove.kluent.`should equal`
import org.junit.Test

/**
 * Unit test for [ServerUrlUtil], which is responsible for accepting the addresses that are
 * typed on a car head unit, including bare IPv6 literals.
 */
class ServerUrlUtilTest {

    @Test
    fun `Should add the http scheme to a plain host name`() {
        ServerUrlUtil.normalize("navidrome.example.com") `should equal`
            "http://navidrome.example.com"
    }

    @Test
    fun `Should keep an explicit https scheme`() {
        ServerUrlUtil.normalize("https://music.example.com:4533") `should equal`
            "https://music.example.com:4533"
    }

    @Test
    fun `Should not bracket a host with a port`() {
        ServerUrlUtil.normalize("192.168.1.10:4533") `should equal`
            "http://192.168.1.10:4533"
    }

    @Test
    fun `Should bracket a bare IPv6 literal without a port`() {
        ServerUrlUtil.normalize("2001:db8:85a3::8a2e:370:7334") `should equal`
            "http://[2001:db8:85a3::8a2e:370:7334]"
    }

    @Test
    fun `Should bracket a bare IPv6 literal that already has a scheme`() {
        ServerUrlUtil.normalize("http://fe80::1") `should equal` "http://[fe80::1]"
    }

    @Test
    fun `Should keep a bracketed IPv6 literal with a port untouched`() {
        ServerUrlUtil.normalize("[2001:db8::1]:4533") `should equal`
            "http://[2001:db8::1]:4533"
    }

    @Test
    fun `Should keep a bracketed IPv6 literal with https and a path`() {
        ServerUrlUtil.normalize("https://[2001:db8::1]:443/navidrome/") `should equal`
            "https://[2001:db8::1]:443/navidrome"
    }

    @Test
    fun `Should strip whitespace and the trailing slash`() {
        ServerUrlUtil.normalize("  http://music.example.com/  ") `should equal`
            "http://music.example.com"
    }

    @Test
    fun `Should reject an address without a host`() {
        ServerUrlUtil.isValid("http://") `should equal` false
        ServerUrlUtil.isValid("") `should equal` false
    }

    @Test
    fun `Should accept IPv6 literals as valid addresses`() {
        ServerUrlUtil.isValid("[2001:db8::1]:4533") `should equal` true
        ServerUrlUtil.isValid("2001:db8::1") `should equal` true
    }

    @Test
    fun `Should return the host without brackets for display`() {
        ServerUrlUtil.hostForDisplay("http://[2001:db8::1]:4533") `should be equal to`
            "2001:db8::1"
    }
}

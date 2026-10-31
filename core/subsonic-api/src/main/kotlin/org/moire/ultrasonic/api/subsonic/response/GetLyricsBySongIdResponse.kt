package org.moire.ultrasonic.api.subsonic.response

import org.moire.ultrasonic.api.subsonic.SubsonicAPIVersions
import org.moire.ultrasonic.api.subsonic.SubsonicError
import org.moire.ultrasonic.api.subsonic.models.LyricsList

/**
 * Response of the OpenSubsonic endpoint that returns the lyrics with their time axis.
 */
class GetLyricsBySongIdResponse(
    status: Status,
    version: SubsonicAPIVersions,
    error: SubsonicError?,
    val lyricsList: LyricsList = LyricsList()
) : SubsonicResponse(status, version, error)

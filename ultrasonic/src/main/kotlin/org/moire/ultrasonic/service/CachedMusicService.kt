/*
 * CachedMusicService.kt
 * Copyright (C) 2009-2022 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */
package org.moire.ultrasonic.service

import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.Pair
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.moire.ultrasonic.api.subsonic.models.AlbumListType
import org.moire.ultrasonic.data.ActiveServerProvider
import org.moire.ultrasonic.data.MetaDatabase
import org.moire.ultrasonic.domain.Album
import org.moire.ultrasonic.domain.Artist
import org.moire.ultrasonic.domain.Bookmark
import org.moire.ultrasonic.domain.ChatMessage
import org.moire.ultrasonic.domain.Genre
import org.moire.ultrasonic.domain.Index
import org.moire.ultrasonic.domain.JukeboxStatus
import org.moire.ultrasonic.domain.Lyrics
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.domain.MusicFolder
import org.moire.ultrasonic.domain.Playlist
import org.moire.ultrasonic.domain.PodcastsChannel
import org.moire.ultrasonic.domain.SearchCriteria
import org.moire.ultrasonic.domain.SearchResult
import org.moire.ultrasonic.domain.Share
import org.moire.ultrasonic.domain.Track
import org.moire.ultrasonic.domain.UserInfo
import org.moire.ultrasonic.util.DownloadedTracks
import org.moire.ultrasonic.util.EntryByDiscAndTrackComparator
import org.moire.ultrasonic.util.LRUCache
import org.moire.ultrasonic.util.Settings
import org.moire.ultrasonic.util.TimeLimitedCache
import org.moire.ultrasonic.util.Util
import timber.log.Timber

@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
class CachedMusicService(private val musicService: MusicService) : MusicService, KoinComponent {
    private val activeServerProvider: ActiveServerProvider by inject()
    private var metaDatabase: MetaDatabase = activeServerProvider.getActiveMetaDatabase()

    // Old style TimeLimitedCache
    private val cachedMusicDirectories: LRUCache<String, TimeLimitedCache<MusicDirectory?>>
    private val cachedAlbum: LRUCache<String, TimeLimitedCache<MusicDirectory?>>
    private val cachedPlaylistTracks: LRUCache<String, TimeLimitedCache<MusicDirectory?>>
    private val cachedUserInfo: LRUCache<String, TimeLimitedCache<UserInfo?>>
    private val cachedLicenseValid = TimeLimitedCache<Boolean>(120, TimeUnit.SECONDS)
    private val cachedPlaylists = TimeLimitedCache<List<Playlist>?>(3600, TimeUnit.SECONDS)
    private val cachedPodcastsChannels =
        TimeLimitedCache<List<PodcastsChannel>?>(3600, TimeUnit.SECONDS)
    private val cachedGenres = TimeLimitedCache<List<Genre>>(10 * 3600, TimeUnit.SECONDS)

    // New Room Database
    private var cachedArtists = metaDatabase.artistDao()
    private var cachedAlbums = metaDatabase.albumDao()
    private var cachedTracks = metaDatabase.trackDao()
    private var cachedIndexes = metaDatabase.indexDao()
    private val cachedMusicFolders = metaDatabase.musicFoldersDao()

    private var restUrl: String? = null
    private var cachedMusicFolderId: String? = null

    @Throws(Exception::class)
    override fun ping() {
        checkSettingsChanged()
        musicService.ping()
    }

    @Throws(Exception::class)
    override fun isLicenseValid(): Boolean {
        checkSettingsChanged()
        return cachedLicenseValid.get() ?: try {
            musicService.isLicenseValid().also { cachedLicenseValid.set(it) }
        } catch (e: Exception) {
            cachedLicenseValid.getStale() ?: throw e
        }
    }

    @Throws(Exception::class)
    override fun getMusicFolders(refresh: Boolean): List<MusicFolder> {
        checkSettingsChanged()
        val existing = cachedMusicFolders.get()
        if (!refresh && existing.isNotEmpty()) {
            return existing
        }
        return try {
            val result = musicService.getMusicFolders(refresh)
            if (refresh) {
                cachedMusicFolders.clear()
            }
            cachedMusicFolders.set(result)
            result
        } catch (e: Exception) {
            if (existing.isNotEmpty()) {
                Timber.w(e, "Network failed in getMusicFolders, using cached Room data")
                existing
            } else {
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun getIndexes(musicFolderId: String?, refresh: Boolean): List<Index> {
        checkSettingsChanged()

        val existing = if (musicFolderId == null) {
            cachedIndexes.get()
        } else {
            cachedIndexes.get(musicFolderId)
        }

        if (!refresh && existing.isNotEmpty()) {
            return existing
        }

        return try {
            val indexes = musicService.getIndexes(musicFolderId, refresh)
            if (refresh) {
                cachedIndexes.clear()
                cachedMusicDirectories.clear()
            }
            cachedIndexes.upsert(indexes)
            indexes
        } catch (e: Exception) {
            if (existing.isNotEmpty()) {
                Timber.w(e, "Network failed in getIndexes, using cached Room data")
                existing
            } else {
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun getArtists(refresh: Boolean): List<Artist> {
        checkSettingsChanged()

        val existing = cachedArtists.get()
        if (!refresh && existing.isNotEmpty()) {
            return existing
        }

        return try {
            val result = musicService.getArtists(refresh)
            if (refresh) {
                cachedArtists.clear()
            }
            cachedArtists.set(result)
            result
        } catch (e: Exception) {
            if (existing.isNotEmpty()) {
                Timber.w(e, "Network failed in getArtists, using cached Room data")
                existing
            } else {
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun getMusicDirectory(id: String, name: String?, refresh: Boolean): MusicDirectory {
        checkSettingsChanged()
        val existingCache = cachedMusicDirectories[id]
        val fresh = if (refresh) null else existingCache?.get()
        if (fresh != null) return fresh
        return try {
            val fetched = musicService.getMusicDirectory(id, name, refresh)
            val cache = TimeLimitedCache<MusicDirectory?>(
                Settings.directoryCacheTime.toLong(), TimeUnit.SECONDS
            )
            cache.set(fetched)
            cachedMusicDirectories.put(id, cache)
            fetched
        } catch (e: Exception) {
            val stale = existingCache?.getStale()
            if (stale != null) {
                Timber.w(e, "Network failed in getMusicDirectory, using stale cache")
                stale
            } else {
                throw e
            }
        }
    }

    /*
     * Retrieves all albums of the provided artist.
     * Cached in the RoomDB
     */
    @Throws(Exception::class)
    override fun getAlbumsOfArtist(id: String, name: String?, refresh: Boolean):
        List<Album> {
        checkSettingsChanged()

        val existing = cachedAlbums.byArtist(id)
        if (!refresh && existing.isNotEmpty()) {
            return existing
        }

        return try {
            val result = musicService.getAlbumsOfArtist(id, name, refresh)
            if (refresh) {
                cachedAlbums.clearByArtist(id)
            }
            cachedAlbums.upsert(result)
            result
        } catch (e: Exception) {
            if (existing.isNotEmpty()) {
                Timber.w(e, "Network failed in getAlbumsOfArtist, using cached Room data")
                existing
            } else {
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun getAlbumAsDir(id: String, name: String?, refresh: Boolean): MusicDirectory {
        checkSettingsChanged()
        val existingCache = cachedAlbum[id]
        val fresh = if (refresh) null else existingCache?.get()
        if (fresh != null) return fresh
        return try {
            val fetched = musicService.getAlbumAsDir(id, name, refresh)
            val cache = TimeLimitedCache<MusicDirectory?>(
                Settings.directoryCacheTime.toLong(), TimeUnit.SECONDS
            )
            cache.set(fetched)
            cachedAlbum.put(id, cache)
            fetched
        } catch (e: Exception) {
            val stale = existingCache?.getStale()
            if (stale != null) {
                Timber.w(e, "Network failed in getAlbumAsDir, using stale cache")
                stale
            } else {
                val roomTracks = cachedTracks
                    .byAlbum(id)
                    .sortedWith(EntryByDiscAndTrackComparator())
                if (roomTracks.isNotEmpty()) {
                    Timber.w(e, "Network failed in getAlbumAsDir, using Room tracks")
                    MusicDirectory().apply {
                        this.name = name
                        addAll(roomTracks)
                    }
                } else {
                    throw e
                }
            }
        }
    }

    @Throws(Exception::class)
    override fun getAlbum(id: String, name: String?, refresh: Boolean): Album? {
        checkSettingsChanged()
        var cache = if (refresh) null else cachedAlbums.get(id)
        if (cache == null) {
            try {
                cache = musicService.getAlbum(id, name, refresh)
            } catch (ignored: Exception) {
                cache = cachedAlbums.get(id)
            }

            cache?.let { cachedAlbums.upsert(it) }
        }
        return cache
    }

    @Throws(Exception::class)
    override fun search(criteria: SearchCriteria): SearchResult? {
        return musicService.search(criteria)
    }

    @Throws(Exception::class)
    override fun getPlaylist(id: String, name: String): MusicDirectory {
        checkSettingsChanged()
        val existingCache = cachedPlaylistTracks[id]
        val fresh = existingCache?.get()
        if (fresh != null) return fresh

        return try {
            val dir = musicService.getPlaylist(id, name)
            val cache = TimeLimitedCache<MusicDirectory?>(
                Settings.directoryCacheTime.toLong(), TimeUnit.SECONDS
            )
            cache.set(dir)
            cachedPlaylistTracks.put(id, cache)
            dir
        } catch (e: Exception) {
            val stale = existingCache?.getStale()
            if (stale != null) {
                Timber.w(e, "Network failed in getPlaylist, using stale cache for %s", name)
                stale
            } else {
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun getPodcastsChannels(refresh: Boolean): List<PodcastsChannel> {
        checkSettingsChanged()
        val fresh = if (refresh) null else cachedPodcastsChannels.get()
        if (fresh != null) return fresh
        return try {
            musicService.getPodcastsChannels(refresh).also { cachedPodcastsChannels.set(it) }
        } catch (e: Exception) {
            cachedPodcastsChannels.getStale() ?: throw e
        }
    }

    @Throws(Exception::class)
    override fun getPodcastEpisodes(podcastChannelId: String?): MusicDirectory? {
        return musicService.getPodcastEpisodes(podcastChannelId)
    }

    @Throws(Exception::class)
    override fun getPlaylists(refresh: Boolean): List<Playlist> {
        checkSettingsChanged()
        val fresh = if (refresh) null else cachedPlaylists.get()
        if (fresh != null) return fresh
        return try {
            musicService.getPlaylists(refresh).also { cachedPlaylists.set(it) }
        } catch (e: Exception) {
            cachedPlaylists.getStale() ?: throw e
        }
    }

    @Throws(Exception::class)
    override fun createPlaylist(id: String?, name: String?, tracks: List<Track>) {
        cachedPlaylists.clear()
        id?.let { cachedPlaylistTracks.put(it, TimeLimitedCache()) }
        musicService.createPlaylist(id, name, tracks)
    }

    @Throws(Exception::class)
    override fun deletePlaylist(id: String) {
        cachedPlaylists.clear()
        musicService.deletePlaylist(id)
    }

    @Throws(Exception::class)
    override fun updatePlaylist(id: String, name: String?, comment: String?, pub: Boolean) {
        cachedPlaylists.clear()
        musicService.updatePlaylist(id, name, comment, pub)
    }

    @Throws(Exception::class)
    override fun getLyrics(artist: String, title: String): Lyrics? {
        return musicService.getLyrics(artist, title)
    }

    override fun getSyncedLyrics(id: String): Lyrics? {
        return musicService.getSyncedLyrics(id)
    }

    @Throws(Exception::class)
    override fun scrobble(id: String, submission: Boolean) {
        musicService.scrobble(id, submission)
    }

    @Throws(Exception::class)
    override fun getAlbumList(
        type: AlbumListType,
        size: Int,
        offset: Int,
        musicFolderId: String?
    ): List<Album> {
        return musicService.getAlbumList(type, size, offset, musicFolderId)
    }

    @Throws(Exception::class)
    override fun getAlbumList2(
        type: AlbumListType,
        size: Int,
        offset: Int,
        musicFolderId: String?
    ): List<Album> {
        return try {
            musicService.getAlbumList2(type, size, offset, musicFolderId)
        } catch (e: Exception) {
            val fallback = when (type) {
                AlbumListType.NEWEST -> cachedAlbums.orderedByAge(size, offset)
                AlbumListType.SORTED_BY_ARTIST -> cachedAlbums.orderedByArtist(size, offset)
                else -> cachedAlbums.orderedByName(size, offset)
            }
            if (fallback.isNotEmpty()) {
                Timber.w(e, "Network failed in getAlbumList2, using Room cached albums")
                fallback
            } else {
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun getRandomSongs(size: Int): MusicDirectory {
        return try {
            musicService.getRandomSongs(size)
        } catch (e: Exception) {
            val localDownloaded = DownloadedTracks.scan()
                .filter { it.state != DownloadedTracks.State.PARTIAL }
                .map { it.track }
                .shuffled()
                .take(size)
            if (localDownloaded.isNotEmpty()) {
                Timber.w(e, "Network failed in getRandomSongs, falling back to downloaded tracks")
                MusicDirectory().apply { addAll(localDownloaded) }
            } else {
                throw e
            }
        }
    }

    @Throws(Exception::class)
    override fun getStarred(): SearchResult = musicService.getStarred()

    @Throws(Exception::class)
    override fun getStarred2(): SearchResult = musicService.getStarred2()

    @Throws(Exception::class)
    override fun getDownloadInputStream(
        song: Track,
        offset: Long,
        maxBitrate: Int,
        save: Boolean
    ): Pair<InputStream, Boolean> {
        return musicService.getDownloadInputStream(song, offset, maxBitrate, save)
    }

    @Throws(Exception::class)
    override fun getStreamUrl(id: String, maxBitRate: Int?, format: String?): String? {
        return musicService.getStreamUrl(id, maxBitRate, format)
    }

    override fun isJukeboxAvailable(): Boolean {
        return musicService.isJukeboxAvailable()
    }

    @Throws(Exception::class)
    override fun updateJukeboxPlaylist(ids: List<String>?): JukeboxStatus {
        return musicService.updateJukeboxPlaylist(ids)
    }

    @Throws(Exception::class)
    override fun skipJukebox(index: Int, offsetSeconds: Int): JukeboxStatus {
        return musicService.skipJukebox(index, offsetSeconds)
    }

    @Throws(Exception::class)
    override fun stopJukebox(): JukeboxStatus {
        return musicService.stopJukebox()
    }

    @Throws(Exception::class)
    override fun startJukebox(): JukeboxStatus {
        return musicService.startJukebox()
    }

    @Throws(Exception::class)
    override fun getJukeboxStatus(): JukeboxStatus = musicService.getJukeboxStatus()

    @Throws(Exception::class)
    override fun setJukeboxGain(gain: Float): JukeboxStatus {
        return musicService.setJukeboxGain(gain)
    }

    @Synchronized
    private fun checkSettingsChanged() {
        val newUrl = activeServerProvider.getRestUrl(null)
        val newFolderId = activeServerProvider.getActiveServer().musicFolderId
        if (!Util.equals(newUrl, restUrl) || !Util.equals(cachedMusicFolderId, newFolderId)) {
            // Switch database
            metaDatabase = activeServerProvider.getActiveMetaDatabase()
            cachedArtists = metaDatabase.artistDao()
            cachedAlbums = metaDatabase.albumDao()
            cachedTracks = metaDatabase.trackDao()
            cachedIndexes = metaDatabase.indexDao()

            // Clear in memory caches
            cachedMusicDirectories.clear()
            cachedLicenseValid.clear()
            cachedPlaylists.clear()
            cachedPlaylistTracks.clear()
            cachedGenres.clear()
            cachedAlbum.clear()
            cachedUserInfo.clear()

            // Set the cache keys
            restUrl = newUrl
            cachedMusicFolderId = newFolderId
        }
    }

    @Throws(Exception::class)
    override fun star(id: String?, albumId: String?, artistId: String?) {
        musicService.star(id, albumId, artistId)
    }

    @Throws(Exception::class)
    override fun unstar(id: String?, albumId: String?, artistId: String?) {
        musicService.unstar(id, albumId, artistId)
    }

    @Throws(Exception::class)
    override fun setRating(id: String, rating: Int) {
        musicService.setRating(id, rating)
    }

    @Throws(Exception::class)
    override fun getGenres(refresh: Boolean): List<Genre> {
        checkSettingsChanged()
        val result = (if (refresh) null else cachedGenres.get()) ?: try {
            musicService.getGenres(refresh).also { cachedGenres.set(it) }
        } catch (e: Exception) {
            cachedGenres.getStale() ?: throw e
        }

        val sorted = result.toMutableList()
        sorted.sortWith { genre, genre2 ->
            genre.name.compareTo(
                genre2.name,
                ignoreCase = true
            )
        }
        return sorted
    }

    @Throws(Exception::class)
    override fun getSongsByGenre(genre: String, count: Int, offset: Int): MusicDirectory {
        return musicService.getSongsByGenre(genre, count, offset)
    }

    @Throws(Exception::class)
    override fun getShares(refresh: Boolean): List<Share> {
        return musicService.getShares(refresh)
    }

    @Throws(Exception::class)
    override fun getChatMessages(since: Long?): List<ChatMessage?>? {
        return musicService.getChatMessages(since)
    }

    @Throws(Exception::class)
    override fun addChatMessage(message: String) {
        musicService.addChatMessage(message)
    }

    @Throws(Exception::class)
    override fun getBookmarks(): List<Bookmark> = musicService.getBookmarks()

    @Throws(Exception::class)
    override fun deleteBookmark(id: String) {
        musicService.deleteBookmark(id)
    }

    @Throws(Exception::class)
    override fun createBookmark(id: String, position: Int) {
        musicService.createBookmark(id, position)
    }

    @Throws(Exception::class)
    override fun getVideos(refresh: Boolean): MusicDirectory? {
        checkSettingsChanged()
        val existingCache = cachedMusicDirectories[CACHE_KEY_VIDEOS]
        var dir = if (refresh) null else existingCache?.get()
        if (dir == null) {
            dir = try {
                val fetched = musicService.getVideos(refresh)
                val cache = TimeLimitedCache<MusicDirectory?>(
                    Settings.directoryCacheTime.toLong(), TimeUnit.SECONDS
                )
                cache.set(fetched)
                cachedMusicDirectories.put(CACHE_KEY_VIDEOS, cache)
                fetched
            } catch (e: Exception) {
                existingCache?.getStale() ?: throw e
            }
        }
        return dir
    }

    @Throws(Exception::class)
    override fun getUser(username: String): UserInfo {
        checkSettingsChanged()
        val existingCache = cachedUserInfo[username]
        val fresh = existingCache?.get()
        if (fresh != null) return fresh
        return try {
            val fetched = musicService.getUser(username)
            val cache = TimeLimitedCache<UserInfo?>(
                Settings.directoryCacheTime.toLong(), TimeUnit.SECONDS
            )
            cache.set(fetched)
            cachedUserInfo.put(username, cache)
            fetched
        } catch (e: Exception) {
            existingCache?.getStale() ?: throw e
        }
    }

    @Throws(Exception::class)
    override fun createShare(
        ids: List<String>,
        description: String?,
        expires: Long?
    ): List<Share> {
        return musicService.createShare(ids, description, expires)
    }

    @Throws(Exception::class)
    override fun deleteShare(id: String) {
        musicService.deleteShare(id)
    }

    @Throws(Exception::class)
    override fun updateShare(id: String, description: String?, expires: Long?) {
        musicService.updateShare(id, description, expires)
    }

    companion object {
        private const val MUSIC_DIR_CACHE_SIZE = 100
        const val CACHE_KEY_VIDEOS = "VIDEOS"
    }

    init {
        cachedMusicDirectories = LRUCache(MUSIC_DIR_CACHE_SIZE)
        cachedAlbum = LRUCache(MUSIC_DIR_CACHE_SIZE)
        cachedPlaylistTracks = LRUCache(MUSIC_DIR_CACHE_SIZE)
        cachedUserInfo = LRUCache(MUSIC_DIR_CACHE_SIZE)
    }
}

package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * lrc.red. Search by free text, then download the song document by ISRC. The document holds a
 * TTML source and a timing level ("word" or "line"); the TTML is authoritative.
 */
internal class LrcRedSource : LyricsSource {

    private val throttle = Throttle(300)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank()) return emptyList()
        val base = baseUrl(settings)
        val term = listOf(track.title, query.artist).filter { it.isNotBlank() }.joinToString(" ")
        throttle.await()
        val body = runCatching { Http.get("$base/search.json?q=" + Http.encode(term)) }.getOrNull()
            ?: return emptyList()
        val hits = runCatching { Http.json(body).obj()?.arr("hits").objects() }.getOrNull()
            ?: return emptyList()

        return hits.mapNotNull { hit ->
            val isrc = hit.str("isrc") ?: return@mapNotNull null
            val title = hit.str("title") ?: return@mapNotNull null
            val artist = hit.str("artist").orEmpty()
            val album = hit.str("album").orEmpty()
            val duration = hit.dbl("duration")?.toLong() ?: 0L
            Triple(isrc, Candidate(title, artist, album, duration), hit)
        }.filter { Match.accept(query, it.second) }
            .sortedByDescending { Match.score(query, it.second) }
            .take(8)
            .map { (isrc, candidate, _) ->
                Lyrics(
                    id = "lrcred:$isrc",
                    title = candidate.title,
                    subtitle = candidate.artist + " • lrc.red",
                    lyrics = null,
                    extras = mapOf("isrc" to isrc, "base" to base),
                )
            }
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val isrc = lyrics.extras["isrc"] ?: throw LyricsException("lrc.red candidate is missing its ISRC")
        val base = lyrics.extras["base"] ?: baseUrl(settings)
        throttle.await()
        val body = try {
            Http.get("$base/s/" + Http.encode(isrc) + ".song.json")
        } catch (error: HttpException) {
            if (error.code == 404) throw LyricsException("lrc.red has no document for this ISRC")
            throw error
        }
        val root = Http.json(body).obj() ?: throw LyricsException("Empty lrc.red response")
        val ttml = root.str("source")
        if (!ttml.isNullOrBlank()) {
            val lines = Ttml.parse(ttml)
            if (lines.isNotEmpty()) return lyrics.copy(lyrics = timed(lines, lines.any { it.words.size > 1 }))
        }
        val lrc = root.str("lyrics")
        if (!lrc.isNullOrBlank()) {
            val lines = Lrc.parse(lrc)
            if (lines.isNotEmpty()) return lyrics.copy(lyrics = timed(lines, lines.any { it.words.size > 1 }))
            val plainLines = Lrc.parsePlain(lrc)
            if (plainLines.isNotEmpty()) return lyrics.copy(lyrics = plain(plainLines.joinToString("\n")))
        }
        throw LyricsException("lrc.red returned no usable lyrics")
    }

    private fun baseUrl(settings: Settings?): String {
        val override = settings?.getString(KEY_ENDPOINT)?.trim().orEmpty()
        return (override.ifEmpty { BASE_URL }).trimEnd('/')
    }

    companion object {
        const val KEY_ENDPOINT = "endpoint"
        private const val BASE_URL = "https://lrc.red"

        fun settings(): List<Setting> = listOf(
            SettingTextInput(
                title = "API endpoint",
                key = KEY_ENDPOINT,
                summary = "Leave blank for https://lrc.red",
                defaultValue = "",
            ),
        )
    }
}

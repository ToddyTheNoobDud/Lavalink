package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.protocol.v4.EncodedTracks
import dev.arbjerg.lavalink.protocol.v4.LoadResult
import dev.arbjerg.lavalink.protocol.v4.Track
import dev.arbjerg.lavalink.protocol.v4.Tracks
import jakarta.servlet.http.HttpServletRequest
import lavalink.server.util.decodeTrack
import lavalink.server.util.toPlaylistInfo
import lavalink.server.util.toPluginInfo
import lavalink.server.util.toTrack
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
class AudioLoaderRestHandler(
    private val audioPlayerManager: AudioPlayerManager,
    private val pluginInfoModifiers: List<AudioPluginInfoModifier>
) {
    companion object {
        private val log = LoggerFactory.getLogger(AudioLoaderRestHandler::class.java)
    }

    @GetMapping("/v4/loadtracks")
    fun loadTracks(
        request: HttpServletRequest,
        @RequestParam identifier: String
    ): ResponseEntity<LoadResult> {
        log.info("Received request to load for identifier: \"$identifier\"")

        val item = try {
            loadAudioItem(audioPlayerManager, identifier)
        } catch (ex: FriendlyException) {
            log.error("Failed to load track for identifier: \"$identifier\"", ex)
            return ResponseEntity.ok(LoadResult.loadFailed(ex))
        }

        return when (item) {
            null -> ResponseEntity.ok(LoadResult.NoMatches())
            is AudioTrack -> {
                log.info("Loaded track: ${item.info.title}")
                ResponseEntity.ok(LoadResult.trackLoaded(item.toTrack(audioPlayerManager, pluginInfoModifiers)))
            }
            is AudioPlaylist -> {
                log.info("Loaded playlist: ${item.name}")
                val tracks = item.tracks.map { it.toTrack(audioPlayerManager, pluginInfoModifiers) }
                if (item.isSearchResult) {
                    ResponseEntity.ok(LoadResult.searchResult(tracks))
                } else {
                    ResponseEntity.ok(LoadResult.playlistLoaded(item.toPlaylistInfo(), item.toPluginInfo(pluginInfoModifiers), tracks))
                }
            }
            else -> {
                val errorMessage = "Unknown item type: ${item.javaClass.canonicalName}"
                log.error(errorMessage)
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, errorMessage)
            }
        }
    }

    @GetMapping("/v4/decodetrack")
    fun getDecodeTrack(@RequestParam encodedTrack: String?, @RequestParam track: String?): ResponseEntity<Track> {
        val trackToDecode = encodedTrack ?: track ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "No track to decode provided"
        )
        
        val decodedTrack = decodeTrack(audioPlayerManager, trackToDecode).toTrack(trackToDecode, pluginInfoModifiers)
        return ResponseEntity.ok(decodedTrack)
    }

    @PostMapping("/v4/decodetracks")
    fun decodeTracks(@RequestBody encodedTracks: EncodedTracks): ResponseEntity<Tracks> {
        if (encodedTracks.tracks.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No tracks to decode provided")
        }

        val decodedTracks = encodedTracks.tracks.map { decodeTrack(audioPlayerManager, it).toTrack(it, pluginInfoModifiers) }
        return ResponseEntity.ok(Tracks(decodedTracks))
    }
}

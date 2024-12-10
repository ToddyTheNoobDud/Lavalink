package lavalink.server.util

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.protocol.v4.*
import kotlinx.serialization.json.JsonObject
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer
import lavalink.server.player.LavalinkPlayer
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*

fun AudioTrack.toTrack(
    audioPlayerManager: AudioPlayerManager,
    pluginInfoModifiers: List<AudioPluginInfoModifier>
): Track {
    return this.toTrack(encodeTrack(audioPlayerManager, this), pluginInfoModifiers)
}

fun AudioTrack.toTrack(encoded: String, pluginInfoModifiers: List<AudioPluginInfoModifier>): Track {
    val pluginInfo = pluginInfoModifiers.fold(JsonObject(emptyMap())) { acc, modifier ->
        acc + (modifier.modifyAudioTrackPluginInfo(this) ?: JsonObject(emptyMap()))
    }
    return Track(encoded, this.toInfo(), pluginInfo, this.userData as? JsonObject ?: JsonObject(emptyMap()))
}

private operator fun JsonObject.plus(other: JsonObject) = JsonObject(this.toMap() + other.toMap())

fun AudioTrack.toInfo(): TrackInfo {
    return TrackInfo(
        this.identifier,
        this.isSeekable,
        this.info.author,
        this.duration,
        this.info.isStream,
        this.position,
        this.info.title,
        this.info.uri,
        this.sourceManager.sourceName,
        this.info.artworkUrl,
        this.info.isrc
    )
}

fun AudioPlaylist.toPlaylistInfo(): PlaylistInfo {
    return PlaylistInfo(this.name, this.selectedTrack?.let { this.tracks.indexOf(it) } ?: -1)
}

fun AudioPlaylist.toPluginInfo(pluginInfoModifiers: List<AudioPluginInfoModifier>): JsonObject {
    return pluginInfoModifiers.fold(JsonObject(emptyMap())) { acc, modifier ->
        acc + (modifier.modifyAudioPlaylistPluginInfo(this) ?: JsonObject(emptyMap()))
    }
}

fun LavalinkPlayer.toPlayer(context: SocketContext, pluginInfoModifiers: List<AudioPluginInfoModifier>): Player {
    val connection = context.getMediaConnection(this).gatewayConnection
    val voiceServerInfo = context.koe.getConnection(guildId)?.voiceServerInfo
    return Player(
        guildId.toString(),
        track?.toTrack(context.audioPlayerManager, pluginInfoModifiers),
        audioPlayer.volume,
        audioPlayer.isPaused,
        PlayerState(
            System.currentTimeMillis(),
            track?.position ?: 0,
            connection?.isOpen ?: false,
            connection?.ping ?: -1
        ),
        VoiceState(
            voiceServerInfo?.token ?: "",
            voiceServerInfo?.endpoint ?: "",
            voiceServerInfo?.sessionId ?: ""
        ),
        filters.toFilters(),
    )
}

fun getRootCause(throwable: Throwable?): Throwable {
    var rootCause = throwable
    while (rootCause?.cause != null) {
        rootCause = rootCause.cause
    }
    return rootCause ?: throwable ?: Exception("Unknown error")
}

fun socketContext(socketServer: SocketServer, sessionId: String): SocketContext =
    socketServer.sessions[sessionId] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found")

fun existingPlayer(socketContext: SocketContext, guildId: Long): LavalinkPlayer =
    socketContext.players[guildId] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found")

fun decodeTrack(audioPlayerManager: AudioPlayerManager, message: String): AudioTrack {
    val decodedBytes = Base64.getDecoder().decode(message)
    ByteArrayInputStream(decodedBytes).use { bais ->
        return audioPlayerManager.decodeTrack(MessageInput(bais)).decodedTrack
            ?: throw IllegalStateException("Failed to decode track due to a mismatching version or missing source manager")
    }
}

fun encodeTrack(audioPlayerManager: AudioPlayerManager, track: AudioTrack): String {
    ByteArrayOutputStream().use { baos ->
        audioPlayerManager.encodeTrack(MessageOutput(baos), track)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}

fun Exception.Severity.Companion.fromFriendlyException(e: FriendlyException.Severity) = when (e) {
    FriendlyException.Severity.COMMON -> Exception.Severity.COMMON
    FriendlyException.Severity.SUSPICIOUS -> Exception.Severity.SUSPICIOUS
    FriendlyException.Severity.FAULT -> Exception.Severity.FAULT
}

fun FriendlyException.Severity.toLavalink() = when (this) {
    FriendlyException.Severity.COMMON -> Exception.Severity.COMMON
    FriendlyException.Severity.SUSPICIOUS -> Exception.Severity.SUSPICIOUS
    FriendlyException.Severity.FAULT -> Exception.Severity.FAULT
}

fun Exception.Companion.fromFriendlyException(e: FriendlyException) = Exception(
    e.message,
    Exception.Severity.fromFriendlyException(e.severity),
    e.toString()
)

fun AudioTrackEndReason.toLavalink() = when (this) {
    AudioTrackEndReason.FINISHED -> Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason.FINISHED
    AudioTrackEndReason.LOAD_FAILED -> Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason.LOAD_FAILED
    AudioTrackEndReason.STOPPED -> Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason.STOPPED
    AudioTrackEndReason.REPLACED -> Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason.REPLACED
    AudioTrackEndReason.CLEANUP -> Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason.CLEANUP
}

fun LoadResult.Companion.loadFailed(exception: FriendlyException) =
    loadFailed(Exception.fromFriendlyException(exception))

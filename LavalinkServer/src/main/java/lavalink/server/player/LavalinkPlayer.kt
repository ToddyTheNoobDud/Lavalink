package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.api.IPlayer
import io.netty.buffer.ByteBuf
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer.Companion.sendPlayerUpdate
import lavalink.server.player.filters.FilterChain
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.media.OpusAudioFrameProvider
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LavalinkPlayer(
    override val socketContext: SocketContext,
    override val guildId: Long,
    private val serverConfig: ServerConfig,
    audioPlayerManager: AudioPlayerManager,
    pluginInfoModifiers: List<AudioPluginInfoModifier>
) : AudioEventAdapter(), IPlayer {

    private val buffer: ByteBuffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize())
    private val mutableFrame: MutableAudioFrame = MutableAudioFrame().apply { setBuffer(buffer) }
    private val audioLossCounter = AudioLossCounter()
    private var updateFuture: ScheduledFuture<*>? = null

    var endMarkerHit = false
    var filters: FilterChain = FilterChain()
        set(value) {
            audioPlayer.setFilterFactory(value.takeIf { it.isEnabled })
            field = value
        }

    override val audioPlayer: AudioPlayer = audioPlayerManager.createPlayer().apply {
        addListener(this@LavalinkPlayer)
        addListener(EventEmitter(audioPlayerManager, this@LavalinkPlayer, pluginInfoModifiers))
        addListener(audioLossCounter)
    }

    override val isPlaying: Boolean
        get() = audioPlayer.playingTrack != null && !audioPlayer.isPaused

    override val track: AudioTrack?
        get() = audioPlayer.playingTrack

    fun destroy() {
        audioPlayer.destroy()
        updateFuture?.cancel(false)
    }

    fun provideTo(connection: MediaConnection) {
        connection.audioSender = Provider(connection)
    }

    override fun play(track: AudioTrack) {
        audioPlayer.playTrack(track)
        sendPlayerUpdate(socketContext, this)
    }

    override fun stop() {
        audioPlayer.stopTrack()
    }

    override fun setPause(pause: Boolean) {
        audioPlayer.isPaused = pause
    }

    override fun seekTo(position: Long) {
        val track = audioPlayer.playingTrack ?: throw IllegalStateException("Can't seek when not playing anything")
        track.position = position
    }

    override fun setVolume(volume: Int) {
        audioPlayer.volume = volume
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        updateFuture?.cancel(false)
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        if (updateFuture?.isCancelled == false) return

        updateFuture = socketContext.playerUpdateService.scheduleAtFixedRate(
            { sendPlayerUpdate(socketContext, this) },
            0,
            serverConfig.playerUpdateInterval.toLong(),
            TimeUnit.SECONDS
        )
    }

    private inner class Provider(connection: MediaConnection?) : OpusAudioFrameProvider(connection) {
        override fun canProvide(): Boolean {
            val provided = audioPlayer.provide(mutableFrame)
            if (!provided) {
                audioLossCounter.onLoss()
            }
            return provided
        }

        override fun retrieveOpusFrame(buf: ByteBuf) {
            audioLossCounter.onSuccess()
            buf.writeBytes(buffer.flip())
        }
    }
}

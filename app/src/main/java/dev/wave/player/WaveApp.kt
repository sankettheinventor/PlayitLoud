package dev.wave.player

import android.app.Application
import dev.wave.player.cache.MediaCache
import dev.wave.player.playback.AudioFx

class WaveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AudioFx.load(this)
        MediaCache.init(this)
        // Android can kill the app without warning, so the "session end" cleanup
        // also runs at every launch: keep intros + last-played songs, drop the rest.
        MediaCache.sweepAsync(this)
    }
}

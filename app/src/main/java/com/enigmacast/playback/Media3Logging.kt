package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

/** Puts [Media3LogGate] in front of Media3's default logger (once, at app start). */
object Media3Logging {
    @OptIn(UnstableApi::class)
    fun install(release: Boolean) {
        val gate = Media3LogGate(release)
        val target = Log.Logger.DEFAULT
        Log.setLogger(object : Log.Logger {
            override fun d(tag: String, message: String, throwable: Throwable?) {
                gate.admit(tag, message)?.let { target.d(tag, it, throwable) }
            }

            override fun i(tag: String, message: String, throwable: Throwable?) {
                gate.admit(tag, message)?.let { target.i(tag, it, throwable) }
            }

            override fun w(tag: String, message: String, throwable: Throwable?) {
                gate.admit(tag, message)?.let { target.w(tag, it, throwable) }
            }

            override fun e(tag: String, message: String, throwable: Throwable?) {
                gate.admit(tag, message)?.let { target.e(tag, it, throwable) }
            }
        })
    }
}

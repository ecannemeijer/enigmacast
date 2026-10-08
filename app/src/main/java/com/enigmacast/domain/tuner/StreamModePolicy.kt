package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.StreamMode

/**
 * What each viewing mode does with the receiver.
 *
 * [StreamMode.OWN_TUNER]: this device streams a channel straight from the receiver's
 * stream port without zapping it. The receiver is put in standby so its own screen
 * is off; the app draws its own channel banner and list, and remote keys stay in the app.
 *
 * [StreamMode.FOLLOW_RECEIVER]: the original behaviour. The app zaps the receiver,
 * streams what it shows, overlays the receiver's OSD and forwards the remote.
 */
object StreamModePolicy {
    fun zapsReceiver(mode: StreamMode) = mode == StreamMode.FOLLOW_RECEIVER
    fun asksToWakeReceiver(mode: StreamMode, inStandby: Boolean) = mode == StreamMode.FOLLOW_RECEIVER && inStandby
    fun stopsStreamOnStandby(mode: StreamMode) = mode == StreamMode.FOLLOW_RECEIVER
    fun mirrorsReceiverOsd(mode: StreamMode) = mode == StreamMode.FOLLOW_RECEIVER
    fun forwardsRemoteKeys(mode: StreamMode) = mode == StreamMode.FOLLOW_RECEIVER
    fun checksTuners(mode: StreamMode) = mode == StreamMode.OWN_TUNER

    /** Own tuner turns the receiver's screen off; follow mode needs the receiver on. */
    fun putsReceiverInStandby(mode: StreamMode) = mode == StreamMode.OWN_TUNER
}

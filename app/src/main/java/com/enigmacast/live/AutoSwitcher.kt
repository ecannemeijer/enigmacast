package com.enigmacast.live

import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.reminder.Reminder
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Switches to an AutoSwitch reminder's channel the way the viewer watches: with an own tuner
 * the app's own stream zaps (the usual tuner check applies, so a full receiver shows the normal
 * "tuners full" message in the player); following the receiver, the receiver itself zaps.
 */
@Singleton
class AutoSwitcher @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val ownTuner: OwnTunerSession,
    private val serviceSource: ServiceStateSource,
    private val playlistSync: com.enigmacast.data.xtream.IptvPlaylistSync,
    private val playlistStore: com.enigmacast.data.xtream.IptvPlaylistStore,
    private val channelSwitch: com.enigmacast.data.channel.ChannelSwitchBus,
) {
    enum class Result { SWITCHED, ZAP_FAILED, NOT_THIS_RECEIVER }

    suspend fun switchTo(reminder: Reminder): Result {
        com.enigmacast.ui.xtream.IptvGuideRows.streamId(reminder.serviceReference)?.let { streamId ->
            return switchIptv(reminder, streamId)
        }
        val settings = settingsRepository.current()
        val receiver = settings.activeReceiver() ?: return Result.NOT_THIS_RECEIVER
        if (reminder.receiverId.isNotBlank() && reminder.receiverId != receiver.id) {
            EnigmaLog.service("autoswitch skipped: set for another receiver")
            return Result.NOT_THIS_RECEIVER
        }
        if (settings.streamMode == StreamMode.FOLLOW_RECEIVER) {
            return try {
                serviceSource.zap(receiver, reminder.serviceReference)
                Result.SWITCHED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                EnigmaLog.service("autoswitch zap failed: ${error.javaClass.simpleName}")
                Result.ZAP_FAILED
            }
        }
        val known = ownTuner.state.value.channels.firstOrNull {
            BouquetChannels.sameService(it.reference, reminder.serviceReference)
        }
        ownTuner.start(known ?: BouquetEntry(reminder.channelName, reminder.serviceReference))
        return Result.SWITCHED
    }

    private suspend fun switchIptv(reminder: Reminder, streamId: String): Result {
        val matches = runCatching { playlistSync.liveChannels(listOf(streamId)) }.getOrDefault(emptyList()).map { it.second }
        val item = matches.firstOrNull { reminder.providerId.isBlank() || it.providerId == reminder.providerId }
            ?: matches.firstOrNull()
            ?: return Result.ZAP_FAILED
        val account = playlistStore.providers.value.firstOrNull { it.id == item.providerId }?.account()
            ?: playlistStore.login.value.xtreamAccount()
        channelSwitch.publish(com.enigmacast.domain.channel.ChannelSwitchRequest.Iptv(item, account))
        return Result.SWITCHED
    }
}


package com.enigmacast.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * ExoPlayer's view of a [TimeshiftStore]. [read] blocks at the live edge and does not
 * report the end of the input while the pump is still writing, so a pause does not
 * look like a stream that finished.
 */
@OptIn(UnstableApi::class)
internal class TimeshiftDataSource(
    private val store: TimeshiftStore,
    private val byteCounter: TransferListener,
) : DataSource {
    private var transferListener: TransferListener? = null
    private var dataSpec = DataSpec(Uri.EMPTY)
    private var uri: Uri = Uri.EMPTY
    private var position = 0L

    @Volatile
    private var open = false

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        uri = dataSpec.uri
        position = dataSpec.position
        open = true
        eachListener { it.onTransferStart(this, dataSpec, false) }
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (open) {
            val error = store.failure()
            if (error != null) throw error
            val n = store.read(position, buffer, offset, length)
            if (n > 0) {
                position += n
                store.discardBefore(position)
                eachListener { it.onBytesTransferred(this, dataSpec, false, n) }
                return n
            }
            if (n < 0) throw IOException("timeshift position dropped")
            if (store.isClosed()) return C.RESULT_END_OF_INPUT
            store.await(position, WAIT_MS) { open }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri = uri

    override fun close() {
        if (!open) return
        open = false
        store.wake()
        eachListener { it.onTransferEnd(this, dataSpec, false) }
    }

    private inline fun eachListener(notify: (TransferListener) -> Unit) {
        notify(byteCounter)
        val extra = transferListener
        if (extra != null && extra !== byteCounter) notify(extra)
    }

    private companion object {
        const val WAIT_MS = 500L
    }
}

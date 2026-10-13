package com.enigmacast.domain.recording

/** The three My Items sections. */
data class MyItemsSections(val device: List<Recording>, val onReceiver: List<Recording>, val scheduledOnReceiver: List<Recording>) {
    val all: List<Recording> get() = device + onReceiver + scheduledOnReceiver

    companion object {
        /**
         * Where a recording lives decides its section, not which device scheduled it:
         * - on this device: captures stored here (place TV);
         * - on the receiver: every recording in the box's movie list, plus this app's box
         *   recordings that are recording or done;
         * - scheduled on the receiver: timers still to come, the app's own included.
         */
        fun split(book: List<Recording>, boxMovies: List<Recording>, boxTimers: List<Recording>): MyItemsSections {
            val device = book.filter { it.place == RecordingPlace.TV }
            val ourBox = book.filter { it.place == RecordingPlace.RECEIVER }
            val ourScheduled = ourBox.filter { it.status == RecordingStatus.SCHEDULED }
            val ourStored = ourBox - ourScheduled.toSet()
            val stored = (ourStored + boxMovies).distinctBy { it.fileName.ifBlank { it.id } }.sortedByDescending { it.beginEpochSec }
            val scheduled = (ourScheduled + boxTimers).sortedBy { it.beginEpochSec }
            return MyItemsSections(device, stored, scheduled)
        }
    }
}

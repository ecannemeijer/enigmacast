package com.enigmacast.domain.sync

import com.enigmacast.domain.sync.ChannelSyncDecision.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelListsTest {
    private val base = listOf(
        BouquetSnapshot("b1", "Favourites", listOf("1:0:1:A", "1:0:1:B")),
        BouquetSnapshot("b2", "Sport", listOf("1:0:1:C")),
    )
    private val hash = ChannelListHash.of(base)

    @Test fun sameListsSameHash() = assertEquals(hash, ChannelListHash.of(base.map { it.copy() }))
    @Test fun renamedBouquetChangesHash() = assertNotEquals(hash, ChannelListHash.of(listOf(base[0].copy(name = "Favs"), base[1])))
    @Test fun reorderedBouquetsChangeHash() = assertNotEquals(hash, ChannelListHash.of(base.reversed()))
    @Test fun reorderedChannelsChangeHash() = assertNotEquals(hash, ChannelListHash.of(listOf(base[0].copy(channels = base[0].channels.reversed()), base[1])))
    @Test fun removedChannelChangesHash() = assertNotEquals(hash, ChannelListHash.of(listOf(base[0].copy(channels = listOf("1:0:1:A")), base[1])))
    @Test fun removedBouquetChangesHash() = assertNotEquals(hash, ChannelListHash.of(listOf(base[0])))
    @Test fun channelMovedBetweenBouquetsChangesHash() = assertNotEquals(
        hash,
        ChannelListHash.of(listOf(base[0].copy(channels = listOf("1:0:1:A")), base[1].copy(channels = listOf("1:0:1:B", "1:0:1:C")))),
    )

    @Test fun unchangedBoxKeepsCache() = assertEquals(Action.KEEP, ChannelSyncDecision.decide(false, true, hash, hash))
    @Test fun changedBoxReplaces() = assertEquals(Action.REPLACE, ChannelSyncDecision.decide(false, true, hash, "other"))
    @Test fun emptyCacheReplaces() = assertEquals(Action.REPLACE, ChannelSyncDecision.decide(false, false, hash, hash))
    @Test fun unknownStoredHashReplaces() = assertEquals(Action.REPLACE, ChannelSyncDecision.decide(false, true, "", hash))
    @Test fun forceReplaces() = assertEquals(Action.REPLACE, ChannelSyncDecision.decide(true, true, hash, hash))

    @Test fun identicalReceiverCarriesOver() = assertTrue(ChannelSyncDecision.carryOver(hash, ChannelListHash.of(base)))
    @Test fun differentReceiverDoesNotCarryOver() = assertFalse(ChannelSyncDecision.carryOver(hash, ChannelListHash.of(listOf(base[1]))))
    @Test fun unknownPreviousDoesNotCarryOver() = assertFalse(ChannelSyncDecision.carryOver("", hash))

    @Test fun goneSelectionFallsBackToAllBouquets() =
        assertEquals(setOf("b1", "b2"), HomeBouquets.effective(setOf("removed"), listOf("b1", "b2")))
}

package com.enigmacast.domain.service

import com.enigmacast.domain.model.BouquetEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class BouquetOrderTest {
    private fun ref(file: String) = """1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "$file" ORDER BY bouquet"""

    private val file = """
        #NAME User - Bouquets (TV)
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.gemaakt__datum_.tv" ORDER BY bouquet
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.nederland_cd___favo_buitenland.tv" ORDER BY bouquet
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.film.tv" ORDER BY bouquet
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.stream_nl_nationaal.tv" ORDER BY bouquet
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.stream_zwitserland.tv" ORDER BY bouquet
    """.trimIndent()

    @Test
    fun streamsListedFirstArePutBackWhereBouquetsTvHasThem() {
        val fromReceiver = listOf(
            BouquetEntry("Stream NL", ref("userbouquet.stream_nl_nationaal.tv")),
            BouquetEntry("Stream CH", ref("userbouquet.stream_zwitserland.tv")),
            BouquetEntry("Gemaakt", ref("userbouquet.gemaakt__datum_.tv")),
            BouquetEntry("Nederland", ref("userbouquet.nederland_cd___favo_buitenland.tv")),
            BouquetEntry("Film", ref("userbouquet.film.tv")),
        )
        val ordered = BouquetOrder.apply(fromReceiver, file).map { it.name }
        assertEquals(listOf("Gemaakt", "Nederland", "Film", "Stream NL", "Stream CH"), ordered)
    }

    @Test
    fun anEncodedReferenceStillFindsTheFile() {
        val encoded = "1:7:1:0:0:0:0:0:0:0:FROM%20BOUQUET%20%22userbouquet.film.tv%22%20ORDER%20BY%20bouquet"
        val fromReceiver = listOf(
            BouquetEntry("Stream NL", ref("userbouquet.stream_nl_nationaal.tv")),
            BouquetEntry("Film", encoded),
        )
        val ordered = BouquetOrder.apply(fromReceiver, file).map { it.name }
        assertEquals(listOf("Film", "Stream NL"), ordered)
    }

    @Test
    fun aBouquetMissingFromTheFileStaysAtTheEnd() {
        val fromReceiver = listOf(
            BouquetEntry("Extra", "1:7:1:0:0:0:0:0:0:0:"),
            BouquetEntry("Film", ref("userbouquet.film.tv")),
        )
        val ordered = BouquetOrder.apply(fromReceiver, file).map { it.name }
        assertEquals(listOf("Film", "Extra"), ordered)
    }
}

package com.enigmacast.data.openwebif

/** One AutoTimer from the plugin's XML list. */
data class AutoTimerEntry(val id: String, val name: String, val match: String, val services: List<String>)

/** The AutoTimer plugin answers XML; a small regex reader is enough for id/name/match/services. */
object AutoTimerXml {
    private val timer = Regex("<timer\\b([^>]*)>(.*?)</timer>|<timer\\b([^>]*)/>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val attr = Regex("(\\w+)=\"([^\"]*)\"")
    private val service = Regex("<e2servicereference>([^<]*)</e2servicereference>", RegexOption.IGNORE_CASE)
    private val state = Regex("<e2state>\\s*(\\w+)\\s*</e2state>", RegexOption.IGNORE_CASE)

    fun parse(body: String): List<AutoTimerEntry> = timer.findAll(body).map { m ->
        val attrs = attr.findAll(m.groupValues[1].ifBlank { m.groupValues[3] }).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        AutoTimerEntry(
            id = attrs["id"].orEmpty(),
            name = attrs["name"].orEmpty(),
            match = attrs["match"].orEmpty(),
            services = service.findAll(m.groupValues[2]).map { it.groupValues[1].trim() }.toList(),
        )
    }.toList()

    /** `<e2state>True</e2state>`; a JSON {"result": true} from newer builds counts too. */
    fun success(body: String): Boolean {
        state.find(body)?.let { return it.groupValues[1].equals("true", ignoreCase = true) }
        return body.contains("\"result\": true") || body.contains("\"result\":true")
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
}

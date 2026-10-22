package com.enigmacast.companionbridge

/**
 * Brug tussen de UI (TvTextField) en de Hilt-singleton [CompanionFormHost]. De UI-laag
 * hoeft zo geen Hilt-entrypoint te kennen; MainActivity zet de host bij het starten.
 */
object CompanionFormHub {
    @Volatile
    var host: CompanionFormHost? = null
}

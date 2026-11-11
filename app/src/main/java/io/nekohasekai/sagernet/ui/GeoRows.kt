package io.nekohasekai.sagernet.ui

/** Row model for the country-sectioned node list (NekoBox999). */
sealed interface GeoRow

data class GeoHeader(
    val key: String,
    val title: String,
    val count: Int,
    val expanded: Boolean,
) : GeoRow

data class GeoItem(val profileId: Long) : GeoRow

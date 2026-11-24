package app.immcloudscale.data.immich

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A day section of the photo grid, Google Photos style. */
data class DaySection(val date: LocalDate, val title: String, val subtitle: String?, val assets: List<AssetDto>)

/** The day an asset belongs to: Immich's localDateTime (wall-clock time where it was taken), else fileCreatedAt. */
fun AssetDto.localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    localDateTime?.let { s ->
        runCatching { return OffsetDateTime.parse(s).toLocalDate() }
        runCatching { return LocalDateTime.parse(s.removeSuffix("Z").substringBefore('+')).toLocalDate() }
    }
    return runCatching { OffsetDateTime.parse(fileCreatedAt).atZoneSameInstant(zone).toLocalDate() }
        .getOrElse { LocalDate.of(1970, 1, 1) }
}

fun dayTitle(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date.year == today.year && date.isAfter(today.minusDays(7)) ->
        date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    date.year == today.year -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
    else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale))
}

/** Groups assets (already sorted newest first) into day sections, with the place as subtitle when known. */
fun groupByDay(assets: List<AssetDto>, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): List<DaySection> =
    assets.groupBy { it.localDate() }
        .toSortedMap(compareByDescending { it })
        .map { (date, items) ->
            val place = items.asSequence().mapNotNull { it.exifInfo?.city }.firstOrNull()
                ?.let { city -> listOfNotNull(city, items.firstNotNullOfOrNull { it.exifInfo?.state ?: it.exifInfo?.country }).joinToString(", ") }
            DaySection(date, dayTitle(date, today, locale), place, items)
        }

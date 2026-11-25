package app.immcloudscale.data.immich

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    val accessToken: String,
    val userId: String = "",
    val userEmail: String = "",
    val name: String = "",
)

@Serializable
data class UserDto(val id: String, val email: String = "", val name: String = "")

@Serializable
data class ServerAbout(val version: String = "")

@Serializable
data class ServerStorage(
    val diskSize: String = "",
    val diskUse: String = "",
    val diskAvailable: String = "",
    val diskUsagePercentage: Double = 0.0,
)

@Serializable
data class ExifInfo(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val focalLength: Double? = null,
    val iso: Int? = null,
    val exposureTime: String? = null,
    val exifImageWidth: Int? = null,
    val exifImageHeight: Int? = null,
    val fileSizeInByte: Long? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val description: String? = null,
)

@Serializable
data class PersonDto(
    val id: String,
    val name: String = "",
    val isHidden: Boolean = false,
)

@Serializable
data class AssetDto(
    val id: String,
    /** IMAGE, VIDEO, AUDIO, OTHER */
    val type: String = "IMAGE",
    val originalFileName: String = "",
    val originalMimeType: String? = null,
    val fileCreatedAt: String = "",
    val localDateTime: String? = null,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val duration: String? = null,
    val thumbhash: String? = null,
    val exifInfo: ExifInfo? = null,
    val people: List<PersonDto> = emptyList(),
    /** Which phone uploaded it, and that phone's ID for the file (see PhoneCopies). */
    val deviceId: String = "",
    val deviceAssetId: String = "",
) {
    val isVideo get() = type == "VIDEO"
    /** "0:00:12.345000" → "0:12" for the tile badge. */
    val shortDuration: String?
        get() {
            val d = duration ?: return null
            val parts = d.split(":")
            if (parts.size != 3) return null
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            val sec = parts[2].substringBefore('.').toIntOrNull() ?: 0
            if (h == 0 && m == 0 && sec == 0) return null
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
}

@Serializable
data class DuplicateGroup(val duplicateId: String, val assets: List<AssetDto> = emptyList())

@Serializable
data class LockStatus(val pinCode: Boolean = false, val isElevated: Boolean = false)

@Serializable
data class AssetPage(val items: List<AssetDto> = emptyList(), val nextPage: String? = null, val total: Int = 0)

@Serializable
data class SearchResponse(val assets: AssetPage = AssetPage())

@Serializable
data class AlbumDto(
    val id: String,
    val albumName: String,
    val albumThumbnailAssetId: String? = null,
    val assetCount: Int = 0,
    val shared: Boolean = false,
    val description: String = "",
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
data class PeopleResponse(val people: List<PersonDto> = emptyList(), val total: Int = 0)

@Serializable
data class MemoryData(val year: Int = 0)

@Serializable
data class MemoryDto(
    val id: String,
    val type: String = "on_this_day",
    val data: MemoryData = MemoryData(),
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
data class SharedLinkDto(val id: String, val key: String)

@Serializable
data class UploadResponse(val id: String, val status: String = "created")

@Serializable
data class BulkCheckItem(val id: String, val checksum: String)

@Serializable
data class BulkCheckRequest(val assets: List<BulkCheckItem>)

@Serializable
data class BulkCheckResult(val id: String, val action: String, val assetId: String? = null, val reason: String? = null)

@Serializable
data class BulkCheckResponse(val results: List<BulkCheckResult> = emptyList())

/** Which slice of the library a grid shows. */
sealed interface AssetQuery {
    data object Timeline : AssetQuery
    data object Favorites : AssetQuery
    data object Archive : AssetQuery
    data object Trash : AssetQuery
    data object Videos : AssetQuery
    data class Album(val id: String) : AssetQuery
    data class Person(val id: String) : AssetQuery
    data class Place(val city: String) : AssetQuery
    data class Smart(val text: String) : AssetQuery
}

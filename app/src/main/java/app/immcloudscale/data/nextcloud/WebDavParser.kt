package app.immcloudscale.data.nextcloud

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** Parses WebDAV multistatus responses (PROPFIND / REPORT / SEARCH) from Nextcloud. */
object WebDavParser {
    private const val DAV = "DAV:"
    private const val OC = "http://owncloud.org/ns"
    private const val NC = "http://nextcloud.org/ns"

    private fun doc(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun Element.child(ns: String, name: String): Element? {
        val list = getElementsByTagNameNS(ns, name)
        return if (list.length > 0) list.item(0) as Element else null
    }

    private fun Element.text(ns: String, name: String): String? = child(ns, name)?.textContent?.trim()?.ifEmpty { null }

    /**
     * @param davRootPath encoded path of the user's root, e.g. "/remote.php/dav/files/alice"; hrefs are made
     * relative to it so [CloudFile.path] is "/Documents/a.pdf".
     */
    fun parse(xml: String, davRootPath: String): List<CloudFile> {
        val responses = doc(xml).documentElement.getElementsByTagNameNS(DAV, "response")
        val rootDecoded = decode(davRootPath).trimEnd('/')
        return (0 until responses.length).mapNotNull { i ->
            val r = responses.item(i) as Element
            val href = r.text(DAV, "href") ?: return@mapNotNull null
            // Only use the propstat that returned 200
            val propstats = r.getElementsByTagNameNS(DAV, "propstat")
            val ok = (0 until propstats.length).map { propstats.item(it) as Element }
                .firstOrNull { it.text(DAV, "status")?.contains(" 200 ") == true } ?: return@mapNotNull null
            val prop = ok.child(DAV, "prop") ?: return@mapNotNull null
            val decodedHref = decode(hrefPath(href))
            val rel = decodedHref.substringAfter(rootDecoded, decodedHref).ifEmpty { "/" }
            val isFolder = prop.child(DAV, "resourcetype")?.getElementsByTagNameNS(DAV, "collection")?.length?.let { it > 0 } ?: false
            val path = if (isFolder) rel.trimEnd('/').ifEmpty { "/" } else rel
            val name = prop.text(DAV, "displayname") ?: path.trimEnd('/').substringAfterLast('/')
            val shareTypes = prop.child(OC, "share-types")?.getElementsByTagNameNS(OC, "share-type")?.length ?: 0
            CloudFile(
                path = path,
                name = name,
                isFolder = isFolder,
                size = (if (isFolder) prop.text(OC, "size") else prop.text(DAV, "getcontentlength"))?.toLongOrNull()
                    ?: prop.text(OC, "size")?.toLongOrNull() ?: 0L,
                modified = prop.text(DAV, "getlastmodified")?.let(::parseDate) ?: 0L,
                contentType = prop.text(DAV, "getcontenttype"),
                fileId = prop.text(OC, "fileid")?.toLongOrNull(),
                favorite = prop.text(OC, "favorite") == "1",
                hasPreview = prop.text(NC, "has-preview") == "true",
                shared = shareTypes > 0,
                etag = prop.text(DAV, "getetag")?.trim('"'),
            )
        }
    }

    fun parseQuota(xml: String): Quota {
        val root = doc(xml).documentElement
        val used = root.getElementsByTagNameNS(DAV, "quota-used-bytes").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0
        val avail = root.getElementsByTagNameNS(DAV, "quota-available-bytes").item(0)?.textContent?.trim()?.toLongOrNull() ?: -1
        return Quota(used, avail)
    }

    private fun hrefPath(href: String): String =
        if (href.startsWith("http://") || href.startsWith("https://")) "/" + href.substringAfter("://").substringAfter('/') else href

    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun parseDate(s: String): Long? =
        runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
}

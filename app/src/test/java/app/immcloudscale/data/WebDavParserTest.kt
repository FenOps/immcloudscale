package app.immcloudscale.data

import app.immcloudscale.data.nextcloud.WebDavParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavParserTest {
    private val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
 <d:response>
  <d:href>/remote.php/dav/files/alice%20b/Documents/</d:href>
  <d:propstat><d:prop>
    <d:getlastmodified>Mon, 03 Nov 2025 10:00:00 GMT</d:getlastmodified>
    <d:resourcetype><d:collection/></d:resourcetype>
    <oc:fileid>10</oc:fileid><oc:size>3000</oc:size><oc:favorite>0</oc:favorite>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/alice%20b/Documents/Tax%20return%202025+final.pdf</d:href>
  <d:propstat><d:prop>
    <d:getlastmodified>Tue, 04 Nov 2025 08:30:00 GMT</d:getlastmodified>
    <d:getcontentlength>123456</d:getcontentlength>
    <d:getcontenttype>application/pdf</d:getcontenttype>
    <d:resourcetype/>
    <d:getetag>"abc123"</d:getetag>
    <oc:fileid>11</oc:fileid><oc:favorite>1</oc:favorite>
    <nc:has-preview>true</nc:has-preview>
    <oc:share-types><oc:share-type>3</oc:share-type></oc:share-types>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>https://cloud.example.com/remote.php/dav/files/alice%20b/Photos/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    @Test fun parsesFoldersAndFiles() {
        val items = WebDavParser.parse(xml, "/remote.php/dav/files/alice%20b")
        assertEquals(3, items.size)
        val folder = items[0]
        assertEquals("/Documents", folder.path)
        assertEquals("Documents", folder.name)
        assertTrue(folder.isFolder)
        assertEquals(3000L, folder.size)

        val pdf = items[1]
        assertEquals("/Documents/Tax return 2025+final.pdf", pdf.path)
        assertEquals("Tax return 2025+final.pdf", pdf.name)
        assertFalse(pdf.isFolder)
        assertEquals(123456L, pdf.size)
        assertEquals("application/pdf", pdf.contentType)
        assertEquals(11L, pdf.fileId)
        assertTrue(pdf.favorite)
        assertTrue(pdf.hasPreview)
        assertTrue(pdf.shared)
        assertEquals("abc123", pdf.etag)
        assertEquals(1762245000000L, pdf.modified)
        assertEquals("/Documents", pdf.parent)

        assertEquals("/Photos", items[2].path) // absolute hrefs are handled too
    }

    @Test fun parsesQuota() {
        val q = WebDavParser.parseQuota("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href>
            <d:propstat><d:prop><d:quota-used-bytes>1000</d:quota-used-bytes><d:quota-available-bytes>9000</d:quota-available-bytes></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
        assertEquals(1000L, q.used)
        assertEquals(10000L, q.total)
    }
}

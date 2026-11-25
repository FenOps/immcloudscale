package app.immcloudscale.ui.gui

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/**
 * A pretend home server answering the Immich and Nextcloud requests the app makes, with sample data and
 * drawn pictures, so screenshots show the real app with real-looking content.
 */
class FakeHomeServer {
    val server = MockWebServer()
    val base: String get() = server.url("/").toString().trimEnd('/')
    private val today = LocalDate.now()
    private val images = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private data class Asset(val id: String, val daysAgo: Long, val hour: Int, val video: Boolean, val fav: Boolean, val city: String?, val region: String?)

    private val assets: List<Asset> = buildList {
        var n = 0
        fun day(daysAgo: Long, count: Int, city: String? = null, region: String? = null) = repeat(count) {
            n++
            add(Asset("a$n", daysAgo, 18 - it, video = n % 9 == 4, fav = n % 7 == 2, city = city, region = region))
        }
        day(0, 6)
        day(1, 8, "Annecy", "Haute-Savoie")
        day(3, 5)
        day(9, 10, "Lisbon", "Portugal")
        day(40, 7, "Paris", "Île-de-France")
        day(300, 6, "Chamonix", "Haute-Savoie")
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = runCatching { route(request) }
                .getOrElse { MockResponse().setResponseCode(500).setBody(it.toString()) }
        }
        server.start()
    }

    fun shutdown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun render(key: String, w: Int, h: Int, draw: (Painter) -> Unit) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Painter(Canvas(bmp), w.toFloat(), h.toFloat(), Random(abs(key.substringAfter(':').hashCode()))))
        images[key] = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        bmp.recycle()
    }

    /**
     * Draws every picture up front. Robolectric's native graphics must be used from the test thread, not from
     * the server's request threads, so requests only serve these stored bytes.
     */
    fun prepareImages() {
        assets.forEach {
            render("thumb:${it.id}", 360, 360) { d -> scene(d) }
            render("preview:${it.id}", 1024, 768) { d -> scene(d) }
        }
        repeat(6) { render("person:p$it", 256, 256) { d -> portrait(d) } }
        files.filter { it.preview }.forEach { f ->
            if (f.id == 17) render("nc:${f.id}", 512, 384) { d -> scene(d) } else render("nc:${f.id}", 384, 512) { d -> documentPage(d) }
        }
    }

    private fun jpeg(key: String): MockResponse {
        val bytes = images[key] ?: return MockResponse().setResponseCode(404)
        return MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
    }

    private fun route(r: RecordedRequest): MockResponse {
        val path = r.path!!.substringBefore('?')
        val q = r.requestUrl!!
        return when {
            // ---- Immich ----
            path == "/api/server/ping" -> json("""{"res":"pong"}""")
            path == "/api/server/storage" -> json("""{"diskSize":"3.6 TiB","diskUse":"1.2 TiB","diskAvailable":"2.4 TiB","diskUsagePercentage":33.4}""")
            path == "/api/users/me" -> json("""{"id":"u1","email":"camille@example.com","name":"Camille Martin"}""")
            path == "/api/search/metadata" || path == "/api/search/smart" -> {
                val body = r.body.readUtf8()
                val list = when {
                    "isFavorite" in body -> assets.filter { it.fav }
                    "albumIds" in body -> assets.filter { it.city == "Lisbon" || it.daysAgo == 0L }
                    "personIds" in body -> assets.filterIndexed { i, _ -> i % 3 == 0 }
                    "\"VIDEO\"" in body -> assets.filter { it.video }
                    "archive" in body || "trashedAfter" in body -> emptyList()
                    path.endsWith("smart") -> assets.filter { it.city == "Lisbon" }
                    else -> assets
                }
                json("""{"assets":{"items":[${list.joinToString(",") { assetJson(it) }}],"nextPage":null,"total":${list.size}}}""")
            }
            path == "/api/memories" -> json("[" + listOf(1, 2, 4).joinToString(",") { y ->
                """{"id":"m$y","type":"on_this_day","data":{"year":${today.year - y}},"assets":[${assetJson(assets[(y * 7) % assets.size])}]}"""
            } + "]")
            path == "/api/albums" -> json(if (q.queryParameter("shared") == "true") "[" + album("al4", "Family", "a13", 312, true) + "]" else "[" + listOf(
                album("al1", "Summer in Lisbon", "a24", 142), album("al2", "Hiking the Alps", "a40", 58),
                album("al3", "Paris weekend", "a30", 77), album("al4", "Family", "a13", 312, true),
            ).joinToString(",") + "]")
            path == "/api/people" -> json("""{"people":[""" + listOf("Léa", "Hugo", "Mamie", "Tom", "", "Inès").mapIndexed { i, n ->
                """{"id":"p$i","name":"$n","isHidden":false}"""
            }.joinToString(",") + """],"total":6}""")
            path == "/api/search/cities" -> json("[" + listOf("a10", "a20", "a30", "a37").joinToString(",") { id -> assetJson(assets.first { it.id == id }) } + "]")
            path.startsWith("/api/people/") && path.endsWith("/thumbnail") -> jpeg("person:" + path.split("/")[3])
            path.startsWith("/api/assets/") && path.endsWith("/thumbnail") -> {
                val id = path.split("/")[3]
                jpeg((if (q.queryParameter("size") == "preview") "preview:" else "thumb:") + id)
            }
            path.startsWith("/api/assets/") -> json(assetJson(assets.first { it.id == path.substringAfterLast('/') }, detailed = true))

            // ---- Nextcloud ----
            path == "/nc/status.php" -> json("""{"installed":true,"maintenance":false,"version":"31.0.0"}""")
            path.startsWith("/nc/index.php/core/preview") -> {
                val id = q.queryParameter("fileId") ?: "0"
                jpeg("nc:$id")
            }
            r.method == "PROPFIND" && r.getHeader("Depth") == "0" ->
                MockResponse().setResponseCode(207).setBody(davQuota())
            r.method == "PROPFIND" -> MockResponse().setResponseCode(207).setBody(davListing(path))
            r.method == "SEARCH" -> MockResponse().setResponseCode(207).setBody(davFiles(recent = true))
            r.method == "REPORT" -> MockResponse().setResponseCode(207).setBody(davFiles(favoritesOnly = true))
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun assetJson(a: Asset, detailed: Boolean = false): String {
        val date = today.minusDays(a.daysAgo).atTime(a.hour, 12)
        val exif = buildString {
            append("""{"exifImageWidth":4032,"exifImageHeight":3024,"fileSizeInByte":${2_400_000 + a.id.hashCode() % 900_000}""")
            if (detailed) append(""","make":"Google","model":"Pixel 9","fNumber":1.7,"exposureTime":"1/320","focalLength":6.9,"iso":64""")
            if (a.city != null) append(""","city":"${a.city}","state":"${a.region}","country":"France","latitude":45.8992,"longitude":6.1294""")
            append("}")
        }
        return """{"id":"${a.id}","type":"${if (a.video) "VIDEO" else "IMAGE"}","originalFileName":"PXL_${date.toLocalDate().toString().replace("-", "")}_${a.id}.jpg",""" +
            """"fileCreatedAt":"${date}:00.000Z","localDateTime":"${date}:00.000Z","isFavorite":${a.fav},"isTrashed":false,""" +
            """"duration":"${if (a.video) "0:00:${10 + a.id.drop(1).toInt() % 40}.000000" else "0:00:00.00000"}","exifInfo":$exif,"people":[]}"""
    }

    private fun album(id: String, name: String, thumb: String, count: Int, shared: Boolean = false) =
        """{"id":"$id","albumName":"$name","albumThumbnailAssetId":"$thumb","assetCount":$count,"shared":$shared}"""

    // ---- WebDAV ----
    private data class F(val name: String, val folder: Boolean = false, val size: Long = 0, val type: String? = null, val daysAgo: Long = 1,
                         val fav: Boolean = false, val shared: Boolean = false, val preview: Boolean = false, val id: Int)
    private val files = listOf(
        F("Documents", true, 48_000_000, daysAgo = 2, id = 1), F("Family", true, 1_200_000_000, daysAgo = 5, shared = true, id = 2),
        F("Music", true, 7_400_000_000, daysAgo = 80, id = 3), F("Work", true, 310_000_000, daysAgo = 1, id = 4),
        F("Tax return 2025.pdf", size = 1_204_000, type = "application/pdf", daysAgo = 3, fav = true, preview = true, id = 11),
        F("Budget 2026.xlsx", size = 48_200, type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", daysAgo = 4, preview = true, id = 12),
        F("Trip to Lisbon.docx", size = 22_000, type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document", daysAgo = 9, shared = true, preview = true, id = 13),
        F("Wedding slides.pptx", size = 8_400_000, type = "application/vnd.openxmlformats-officedocument.presentationml.presentation", daysAgo = 30, preview = true, id = 14),
        F("router-backup.tar.gz", size = 52_000_000, type = "application/gzip", daysAgo = 60, id = 15),
        F("Shopping list.md", size = 3_100, type = "text/markdown", daysAgo = 0, fav = true, id = 16),
        F("Kitchen renovation.jpg", size = 3_800_000, type = "image/jpeg", daysAgo = 6, preview = true, id = 17),
    )

    private fun davHeader() = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">"""
    private fun davEntry(f: F): String {
        val href = "/nc/remote.php/dav/files/camille/" + f.name.replace(" ", "%20") + if (f.folder) "/" else ""
        val modified = java.time.ZonedDateTime.now().minusDays(f.daysAgo).minusHours(f.id.toLong())
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        return """<d:response><d:href>$href</d:href><d:propstat><d:prop>
            <d:getlastmodified>$modified</d:getlastmodified>
            ${if (f.folder) "<d:resourcetype><d:collection/></d:resourcetype><oc:size>${f.size}</oc:size>" else "<d:resourcetype/><d:getcontentlength>${f.size}</d:getcontentlength><d:getcontenttype>${f.type}</d:getcontenttype>"}
            <oc:fileid>${f.id}</oc:fileid><oc:favorite>${if (f.fav) 1 else 0}</oc:favorite>
            <nc:has-preview>${f.preview}</nc:has-preview>
            ${if (f.shared) "<oc:share-types><oc:share-type>3</oc:share-type></oc:share-types>" else ""}
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
    }
    private fun davListing(path: String): String {
        val self = """<d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
        return davHeader() + self + (if (path.trimEnd('/').endsWith("camille")) files.joinToString("") { davEntry(it) } else "") + "</d:multistatus>"
    }
    private fun davFiles(recent: Boolean = false, favoritesOnly: Boolean = false): String {
        val list = files.filter { !it.folder && (!favoritesOnly || it.fav) }.let { if (recent) it.sortedBy { f -> f.daysAgo } else it }
        return davHeader() + list.joinToString("") { davEntry(it) } + "</d:multistatus>"
    }
    private fun davQuota() = davHeader() + """<d:response><d:href>/nc/remote.php/dav/files/camille/</d:href><d:propstat><d:prop>
        <d:quota-used-bytes>312000000000</d:quota-used-bytes><d:quota-available-bytes>688000000000</d:quota-available-bytes>
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

    // ---- pictures ----
    class Painter(val c: Canvas, val w: Float, val h: Float, val rnd: Random) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun color(rgb: Int, alpha: Int = 255) { p.shader = null; p.color = (alpha shl 24) or rgb }
        fun vgrad(top: Float, bottom: Float, from: Int, to: Int) {
            p.shader = LinearGradient(0f, top, 0f, bottom, (0xFF shl 24) or from, (0xFF shl 24) or to, Shader.TileMode.CLAMP)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float) = c.drawRect(l, t, r, b, p)
        fun oval(l: Float, t: Float, r: Float, b: Float) = c.drawOval(RectF(l, t, r, b), p)
        fun round(l: Float, t: Float, r: Float, b: Float, rad: Float) = c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)
        fun poly(vararg xy: Float) {
            val path = Path(); path.moveTo(xy[0], xy[1])
            var i = 2
            while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
            path.close(); c.drawPath(path, p)
        }
        fun ridge(base: Float, amp: Float, rgb: Int) {
            color(rgb)
            val path = Path(); path.moveTo(0f, h)
            var y = h * base
            var x = 0f
            while (x <= w + 1) {
                y = (y + (rnd.nextFloat() * 2 - 1) * amp * h).coerceIn(h * (base - .25f), h * (base + .12f))
                path.lineTo(x, y); x += w / 14
            }
            path.lineTo(w, h); path.close(); c.drawPath(path, p)
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt()
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    fun scene(d: Painter) = with(d) {
        when (rnd.nextInt(6)) {
            0 -> { // alpine lake
                vgrad(0f, h * .55f, 0x6FB1E8, 0xD7ECFA); rect(0f, 0f, w, h)
                ridge(.42f, .07f, 0x8A9BB0); ridge(.52f, .05f, 0x51677D)
                vgrad(h * .62f, h, 0x3E8FB8, 0x1D4F6B); rect(0f, h * .62f, w, h)
                ridge(.66f, .02f, 0x2F5D3A)
            }
            1 -> { // sunset over the sea
                vgrad(0f, h * .6f, 0x2D3A7A, 0xFF9E5E); rect(0f, 0f, w, h)
                color(0xFFD27F); oval(w * .55f, h * .42f, w * .73f, h * .42f + w * .18f)
                vgrad(h * .6f, h, 0xE0784F, 0x2A2350); rect(0f, h * .6f, w, h)
                color(0xFFDCA0, 90); repeat(8) { i -> val x = w * .5f + rnd.nextFloat() * w / 6; rect(x, h * (.63f + i * .04f), x + w / 5, h * (.63f + i * .04f) + 3) }
            }
            2 -> { // forest
                vgrad(0f, h, 0xBFD8C2, 0x7FA57F); rect(0f, 0f, w, h)
                repeat(3) { layer ->
                    color(mix(0x5E8C61, 0x1F3D26, layer / 2f))
                    repeat(12) {
                        val x = rnd.nextFloat() * w; val tw = w / 9 + rnd.nextFloat() * w / 12; val top = h * (.25f + layer * .15f + rnd.nextFloat() * .1f)
                        poly(x, top, x + tw / 2, h, x - tw / 2, h)
                    }
                }
            }
            3 -> { // city at night
                vgrad(0f, h, 0x0B1030, 0x3A2A6B); rect(0f, 0f, w, h)
                var x = 0f
                while (x < w) {
                    val bw = w / 12 + rnd.nextFloat() * w / 10; val bh = h * (.25f + rnd.nextFloat() * .45f)
                    color(mix(0x1A1F3D, 0x2C2F55, rnd.nextFloat())); rect(x, h - bh, x + bw, h)
                    color(0xFFD57A)
                    var wy = h - bh + 8
                    while (wy < h - 6) { var wx = x + 5; while (wx < x + bw - 6) { if (rnd.nextInt(3) == 0) rect(wx, wy, wx + 5, wy + 7); wx += 11 }; wy += 14 }
                    x += bw + 2
                }
                color(0xF4F1E0); oval(w * .78f, h * .1f, w * .86f, h * .1f + w * .08f)
            }
            4 -> { // beach
                vgrad(0f, h * .5f, 0x58B7E8, 0xBFE6F7); rect(0f, 0f, w, h)
                color(0x1F8FB8); rect(0f, h * .5f, w, h)
                vgrad(h * .6f, h, 0xF1DDB0, 0xD9B98A); poly(0f, h * .68f, w, h * .6f, w, h, 0f, h)
                color(0xFFFFFF, 170); p.strokeWidth = 3f; c.drawLine(0f, h * .67f, w, h * .59f, p)
                color(0xFFF3C4); oval(w * .12f, h * .1f, w * .24f, h * .1f + w * .12f)
            }
            else -> { // snowy peaks at golden hour
                vgrad(0f, h * .6f, 0xF6C27A, 0xFCE9CF); rect(0f, 0f, w, h)
                ridge(.45f, .09f, 0xEDE7F0); ridge(.58f, .05f, 0x9C8FB0); ridge(.74f, .03f, 0x4B4466)
            }
        }
    }

    fun portrait(d: Painter) = with(d) {
        val bgs = listOf(0xF6D6AD, 0xC8E6C9, 0xBBDEFB, 0xF8BBD0, 0xD1C4E9, 0xFFE0B2)
        val skins = listOf(0xF1C27D, 0xE0AC69, 0xC68642, 0x8D5524, 0xFFDBAC)
        val hairs = listOf(0x3B2A20, 0x6A4E42, 0x1C1C1C, 0xB08D57, 0xA55728, 0xDDDDDD)
        color(bgs[rnd.nextInt(bgs.size)]); rect(0f, 0f, w, h)
        color(listOf(0x5C6BC0, 0x26A69A, 0xEF5350, 0x7E57C2, 0xFFA726)[rnd.nextInt(5)])
        round(w * .12f, h * .72f, w * .88f, h * 1.22f, w * .25f)
        color(skins[rnd.nextInt(skins.size)])
        rect(w * .43f, h * .6f, w * .57f, h * .76f)
        oval(w * .3f, h * .22f, w * .7f, h * .68f)
        color(hairs[rnd.nextInt(hairs.size)])
        oval(w * .27f, h * .14f, w * .73f, h * .4f)
        if (rnd.nextBoolean()) rect(w * .27f, h * .27f, w * .35f, h * .62f)
    }

    fun documentPage(d: Painter) = with(d) {
        color(0xEEF1F5); rect(0f, 0f, w, h)
        color(0xFFFFFF); rect(w / 12, h / 16, w * 11 / 12, h * 15 / 16)
        color(listOf(0x1A73E8, 0x188038, 0xD93025, 0xF9AB00)[rnd.nextInt(4)])
        rect(w / 6, h / 8, w * 2 / 3, h / 8 + h / 22)
        color(0xC4C7CC)
        var y = h / 8 + h / 12
        while (y < h * 13 / 16) {
            rect(w / 6, y, w / 6 + w * (.45f + rnd.nextFloat() * .2f), y + h / 70)
            y += h / 28
        }
    }
}

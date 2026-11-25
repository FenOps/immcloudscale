package app.immcloudscale.ui.account

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import app.immcloudscale.data.pairing.PairCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** When an invite stops working, as people read it: "3:42 PM" (with the date when it isn't today). */
fun inviteTime(expires: String, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val at = runCatching { Instant.parse(expires).atZone(zone) }.getOrNull() ?: return ""
    val time = at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return if (at.toLocalDate() == now.atZone(zone).toLocalDate()) time
    else "$time, ${at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
}

/** The message an admin sends: how to get the app, the deadline, and the link (which also works when pasted). */
fun inviteMessage(serverName: String, invite: PairCode, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    // Messaging apps don't make app links tappable, so copying the whole message is the way in; the link comes last.
    val deadline = inviteTime(invite.expires, zone, now)
    return buildString {
        append("Hi! You're invited to ").append(serverName.ifBlank { "my home server" }).append(" on ImmCloudScale (our photos and files).\n")
        append(inviteValidity(deadline)).append("\n\n")
        append("1. Install the app: https://github.com/FenOps/immcloudscale/releases/latest\n")
        append("   (tap the file ending in .apk, then allow the install)\n")
        append("2. Copy this whole message, open the app and tap \"Paste an invite\".\n\n")
        append("Too late? Just ask me for a new one.\n")
        append(invite.link)
    }
}

/** The one way every screen says how long an invite works. */
fun inviteValidity(deadline: String) = if (deadline.isNotEmpty()) "Works once, for 15 minutes (until $deadline)." else "Works once, for 15 minutes."

/** Whether the link lets a phone join from anywhere (the server is on Tailscale), or only on the home Wi-Fi. */
fun worksFromAnywhere(link: String) = "&ts=" in link || "?ts=" in link

/** Shares the invite as a message with its QR code attached (it can be scanned or picked from the gallery). */
fun shareInvite(ctx: Context, serverName: String, invite: PairCode) {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    // Earlier invite pictures hold links (and keys) that are no use any more.
    dir.listFiles { f -> f.name.startsWith("invite-") }?.forEach { it.delete() }
    val png = File(dir, "invite-${invite.code}.png")
    runCatching {
        val m = QRCodeWriter().encode(invite.link, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 2))
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, inviteMessage(serverName, invite))
        if (png.exists()) {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", png))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    ctx.startActivity(Intent.createChooser(send, "Send invite"))
}

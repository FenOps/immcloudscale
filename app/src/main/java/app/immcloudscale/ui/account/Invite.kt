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
    val deadline = inviteTime(invite.expires, zone, now)
    return buildString {
        append("You're invited to ").append(serverName.ifBlank { "my home server" }).append(" on ImmCloudScale (photos and files).\n\n")
        append("1. Install the app: https://github.com/FenOps/immcloudscale/releases/latest\n")
        append("2. ")
        if (deadline.isNotEmpty()) append("Before ").append(deadline).append(", ")
        append("open this link on your phone, or copy this whole message and tap \"Paste an invite\" in the app:\n")
        append(invite.link).append("\n\n")
        append("The link works once")
        if (deadline.isNotEmpty()) append(" and expires at ").append(deadline)
        append(". If it's too late, ask for a new one.")
    }
}

/** Shares the invite as a message with its QR code attached (it can be scanned or picked from the gallery). */
fun shareInvite(ctx: Context, serverName: String, invite: PairCode) {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
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

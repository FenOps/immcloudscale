package app.immcloudscale.ui.account

import android.content.Context
import android.content.Intent
import app.immcloudscale.data.pairing.IPhoneInvite
import java.time.Instant
import java.time.ZoneId

/**
 * The message that sets someone up on an iPhone or iPad: the Immich app, the server address and a temporary
 * password. Text only, and built just for sending, so the password isn't kept on this phone.
 */
fun iphoneInviteMessage(serverName: String, invite: IPhoneInvite, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val deadline = inviteTime(invite.expires, zone, now)
    return buildString {
        append("Hi! Here's how to see and back up your photos on ").append(serverName.ifBlank { "my home server" }).append(" from your iPhone or iPad.\n\n")
        var n = 1
        append("${n++}. Install Immich from the App Store: https://apps.apple.com/app/immich/id1613945652\n")
        if (invite.photosAway.isNotBlank()) {
            append("${n++}. Install Tailscale: https://apps.apple.com/app/tailscale/id1470499037 . ")
            append("Open it, sign in to the same Tailscale account as the server (ask me which), and leave it on. It lets your photos reach home from anywhere.\n")
        }
        append("${n++}. Open Immich and enter this server address: ").append(invite.photosAway.ifBlank { invite.photosHome }).append('\n')
        if (invite.photosAway.isNotBlank()) append("   (At home you can also use ").append(invite.photosHome).append(" .)\n")
        else append("   (This works on the home Wi-Fi.)\n")
        append("${n++}. Sign in with\n   Email: ").append(invite.email)
        append("\n   Temporary password: ").append(invite.tempPassword).append(" (type the dashes too)\n")
        append("${n++}. Immich then asks you to choose your own password: pick a permanent one you'll remember.\n")
        append("${n++}. When your iPhone asks about photos, tap \"Allow Full Access\". Then tap the cloud icon at the top and turn on Backup.\n\n")
        append(if (deadline.isNotEmpty()) "The temporary password works until $deadline." else "The temporary password works for 15 minutes after I send this.")
        append(" If it runs out, ask me for a new one.\n")
    }
}

/** Hands the message to a messaging app; nothing is saved here. */
fun shareIPhoneInvite(ctx: Context, serverName: String, invite: IPhoneInvite) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, iphoneInviteMessage(serverName, invite))
    }
    ctx.startActivity(Intent.createChooser(send, "Send iPhone invite"))
}

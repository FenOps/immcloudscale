package app.immcloudscale.ui.account

import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.tailscale.TsState
import app.immcloudscale.data.tailscale.TsStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupWhereTest {
    private val ready = AppSettings(pairedServer = "http://192.168.1.49:8790", tsServerIp = "100.64.0.1", tsEnabled = true)

    @Test fun followsTheConnection() {
        val home = backupWhere(ready, atHome = true, TsState(TsStatus.Off))!!
        assertFalse(home.away)
        assertFalse(home.needsNetwork)
        val away = backupWhere(ready, atHome = false, TsState(TsStatus.Connected))!!
        assertTrue(away.away)
        assertFalse(away.needsNetwork)
        assertTrue(away.text.contains("anywhere"))
    }

    @Test fun connectingIsNotAProblem() {
        val w = backupWhere(ready, atHome = false, TsState(TsStatus.Starting))!!
        assertTrue(w.away)
        assertFalse(w.needsNetwork)
    }

    @Test fun awayWithoutRemoteAccessAsksForIt() {
        assertTrue(backupWhere(ready.copy(tsEnabled = false), atHome = false, TsState(TsStatus.Off))!!.needsNetwork)
        assertTrue(backupWhere(ready.copy(tsServerIp = ""), atHome = false, TsState(TsStatus.Off))!!.needsNetwork)
        assertTrue(backupWhere(ready, atHome = false, TsState(TsStatus.NeedsLogin))!!.needsNetwork)
        assertTrue(backupWhere(ready, atHome = false, TsState(TsStatus.Error, error = "x"))!!.needsNetwork)
    }

    @Test fun handSetUpServersShowNothing() {
        assertNull(backupWhere(AppSettings(), atHome = false, TsState(TsStatus.Off)))
    }

    @Test fun onlyOnWiFiWaitsOnMobileData() {
        val w = backupWhere(ready.copy(backupWifiOnly = true), atHome = false, TsState(TsStatus.Connected), unmetered = false)!!
        assertTrue(w.waiting)
        assertFalse(w.needsNetwork)
        assertTrue(backupWhere(ready, atHome = false, TsState(TsStatus.Connected), unmetered = false)!!.label.contains("anywhere"))
    }
}

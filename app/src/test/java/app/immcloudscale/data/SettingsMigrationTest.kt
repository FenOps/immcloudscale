package app.immcloudscale.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsMigrationTest {
    @Test fun updatingFromTheVpnVersionDropsItsSettingsAndBacksUpOnMobileData() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val vpnKey = stringPreferencesKey("vpn_config")
        ctx.dataStore.edit {
            it[vpnKey] = "[Interface]\nPrivateKey = secret"
            it[booleanPreferencesKey("backup_anywhere")] = true
            it[booleanPreferencesKey("backup_wifi")] = true
        }
        val store = SettingsStore(ctx)
        store.state.value = store.flow.first() // as the app starts: settings read before the cleanup
        store.dropRetired()
        assertNull(ctx.dataStore.data.first()[vpnKey])
        assertFalse(store.current().backupWifiOnly) // what the app reads, not only what's on disk
        store.update { it.copy(backupWifiOnly = true) }
        store.dropRetired() // nothing left to drop: the person's own choice stays
        assertTrue(store.current().backupWifiOnly)
    }
}

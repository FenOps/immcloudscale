package app.immcloudscale.data

import androidx.test.core.app.ApplicationProvider
import app.immcloudscale.ImmApp
import app.immcloudscale.data.tailscale.TailscaleBridge
import app.immcloudscale.data.tailscale.TailscaleManager
import app.immcloudscale.data.tailscale.TsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An invite join (a link from a message) must never change or reroute the phone's existing setup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InviteJoinTest {
    private class FakeBridge(private val joins: Boolean) : TailscaleBridge {
        val routes = mutableMapOf<String, String>()
        var running = false
        var startedWithKey = ""
        override fun start(dir: String, hostname: String, authKey: String, controlUrl: String) { running = true; startedWithKey = authKey }
        override fun stop() { running = false }
        override fun logout() { running = false }
        override fun setRoute(lanHost: String, tailnetHost: String) { if (tailnetHost.isEmpty()) routes.remove(lanHost) else routes[lanHost] = tailnetHost }
        override fun proxyPort() = if (running) 1080 else 0
        override fun proxyToken() = "t"
        override fun status() = if (running && joins) """{"state":"Running","ip":"100.64.0.9"}""" else """{"state":"Starting"}"""
        override fun logs() = ""
    }

    private val app get() = ApplicationProvider.getApplicationContext<ImmApp>()
    private val paired = AppSettings(
        immichUrl = "http://192.168.1.50:2283", immichToken = "k", immichTokenIsApiKey = true, pairedServer = "http://192.168.1.50:8790",
        pairedServerId = "ics_home", tsServerIp = "100.64.0.1", tsAuthKey = "tskey-home", tsEnabled = true,
    )

    @After fun reset() = runBlocking { app.graph.settings.update { AppSettings() } }

    @Test fun aJoinThatWorksOnlyRoutesTheInvitedServerAndCleansUp() = runBlocking {
        app.graph.settings.update { paired }
        val bridge = FakeBridge(joins = true)
        val ts = TailscaleManager(app, app.graph.settings, CoroutineScope(Dispatchers.Default), bridge)
        ts.connectForInvite("http://10.9.9.9:8790", "100.64.0.66", "tskey-invite", "", onSignIn = {})
        assertEquals(TsStatus.Connected, ts.state.value.status)
        assertEquals(mapOf("10.9.9.9" to "100.64.0.66"), bridge.routes) // the home server is NOT sent to the invite's tailnet
        assertEquals("tskey-invite", bridge.startedWithKey)
        ts.inviteDone()
        assertTrue(bridge.routes.isEmpty())
        assertTrue(!bridge.running)
        assertEquals(paired, app.graph.settings.current()) // nothing from the link was saved
    }

    @Test fun aJoinThatFailsLeavesNothingBehind() = runBlocking {
        app.graph.settings.update { paired }
        val bridge = FakeBridge(joins = false)
        val ts = TailscaleManager(app, app.graph.settings, CoroutineScope(Dispatchers.Default), bridge)
        val failed = runCatching { ts.connectForInvite("http://10.9.9.9:8790", "100.64.0.66", "tskey-x", "https://other.example", onSignIn = {}) }
        assertTrue(failed.isFailure)
        ts.inviteDone()
        assertTrue(bridge.routes.isEmpty())
        assertTrue(!bridge.running)
        assertEquals(TsStatus.Off, ts.state.value.status)
        assertEquals(paired, app.graph.settings.current())
        assertEquals(null, ts.inviteServer)
    }
}

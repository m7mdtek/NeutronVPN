/*
 * Copyright (c) 2024. Proton AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.protonvpn.app.redesign.recents.usecases

import app.cash.turbine.test
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.models.vpn.ConnectionParams
import com.protonvpn.android.models.vpn.VpnCountry
import com.protonvpn.android.redesign.CountryId
import com.protonvpn.android.redesign.vpn.AnyConnectIntent
import com.protonvpn.android.redesign.vpn.ChangeServerManager
import com.protonvpn.android.redesign.vpn.ConnectIntent
import com.protonvpn.android.redesign.vpn.ui.ChangeServerViewState
import com.protonvpn.android.redesign.vpn.ui.ChangeServerViewStateFlow
import com.protonvpn.android.servers.ServerManager2
import com.protonvpn.android.settings.data.EffectiveCurrentUserSettings
import com.protonvpn.android.settings.data.LocalUserSettings
import com.protonvpn.android.vpn.ConnectTrigger
import com.protonvpn.android.vpn.VpnConnectionManager
import com.protonvpn.android.vpn.VpnState
import com.protonvpn.android.vpn.VpnStateMonitor
import com.protonvpn.android.vpn.VpnStatusProviderUI
import com.protonvpn.android.vpn.VpnUiDelegate
import com.protonvpn.test.shared.TestCurrentUserProvider
import com.protonvpn.test.shared.TestUser
import com.protonvpn.test.shared.createServer
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val LONG_CONNECT_TIME = 7.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeServerTests {

    @MockK
    private lateinit var mockVpnConnectionManager: VpnConnectionManager

    @MockK
    private lateinit var mockServerManager: ServerManager2

    @MockK
    private lateinit var mockVpnUiDelegate: VpnUiDelegate

    private lateinit var currentUser: CurrentUser
    private lateinit var testScope: TestScope
    private lateinit var testUserProvider: TestCurrentUserProvider
    private lateinit var vpnStateMonitor: VpnStateMonitor

    private lateinit var changeServerManager: ChangeServerManager
    private lateinit var changeServerViewStateFlow: ChangeServerViewStateFlow

    private val server = createServer("server")
    private val connectionParams = ConnectionParams(ConnectIntent.Fastest, server, null, null)
    private var serversByCountry = mapOf(
        "DE" to createServer("server-de", exitCountry = "DE", tier = 0),
        "FR" to createServer("server-fr", exitCountry = "FR", tier = 0),
        "PL" to server,
        "SE" to createServer("server-se", exitCountry = "SE", tier = 0),
    )
    private val freeUser = TestUser.freeUser.vpnUser
    private val plusUser = TestUser.plusUser.vpnUser

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        testScope = TestScope()
        testUserProvider = TestCurrentUserProvider(vpnUser = freeUser)
        currentUser = CurrentUser(testUserProvider)
        vpnStateMonitor = VpnStateMonitor()
        val vpnStatusProviderUI = VpnStatusProviderUI(testScope.backgroundScope, vpnStateMonitor)

        val effectiveUserSettings = EffectiveCurrentUserSettings(testScope.backgroundScope, flowOf(LocalUserSettings()))

        coEvery { mockServerManager.getVpnCountries() } answers {
            listOf("DE", "FR", "PL", "SE").map { code ->
                VpnCountry(code, listOf(serversByCountry.getValue(code)))
            }
        }
        coEvery { mockServerManager.getBestServerForConnectIntent(any(), any(), any(), any()) } answers {
            val intent = firstArg<AnyConnectIntent>() as ConnectIntent.FastestInCountry
            serversByCountry.getValue(intent.country.countryCode)
        }
        coEvery { mockVpnConnectionManager.connect(any(), any(), any()) } answers {
            val intent = secondArg<AnyConnectIntent>()
            val connectedCountry = (intent as? ConnectIntent.Server)?.exitCountry?.countryCode
            connectedCountry?.let(connectedCountries::add)
            val connectedServer = connectedCountry?.let(serversByCountry::get) ?: server
            vpnStateMonitor.updateStatus(
                VpnStateMonitor.Status(
                    VpnState.Connected,
                    ConnectionParams(intent, connectedServer, null, null)
                )
            )
        }

        changeServerManager = ChangeServerManager(
            testScope.backgroundScope,
            vpnStatusProviderUI,
            { mockVpnConnectionManager },
            mockServerManager,
            currentUser,
            effectiveUserSettings,
        )
        changeServerViewStateFlow = ChangeServerViewStateFlow(
            vpnStatusProviderUI,
            changeServerManager,
            currentUser,
        )

        runBlocking {
            // Enable the flow. It is marked as starting lazily to avoid doing unnecessary work for paid users.
            changeServerManager.hasTroubleConnecting.first()
        }
    }

    @Test
    fun `change server shown after first connection`() = testScope.runTest {
        changeServerViewStateFlow.test {
            assertNull(awaitItem())
            vpnStateMonitor.updateStatus(VpnStateMonitor.Status(VpnState.Connected, connectionParams))
            assertEquals(ChangeServerViewState.Unlocked, awaitItem())
        }
    }

    @Test
    fun `change server shown while reconnecting due to server change`() = testScope.runTest {
        coEvery { mockVpnConnectionManager.connect(any(), any(), any()) } answers {
            vpnStateMonitor.updateStatus(VpnStateMonitor.Status(VpnState.Connecting, connectionParams))
        }
        changeServerViewStateFlow.test {
            assertNull(awaitItem())
            vpnStateMonitor.updateStatus(VpnStateMonitor.Status(VpnState.Connected, connectionParams))
            assertEquals(ChangeServerViewState.Unlocked, awaitItem())
            changeServerManager.changeServer(mockVpnUiDelegate)
            runCurrent()
            assertEquals(ChangeServerViewState.Disabled, expectMostRecentItem())
        }
    }

    @Test
    fun `change server stays available after repeated attempts`() = testScope.runTest {
        changeServerViewStateFlow.test {
            assertNull(awaitItem())
            updateVpnStatus(VpnState.Connected)
            assertEquals(ChangeServerViewState.Unlocked, awaitItem())
            changeServerManager.changeServer(mockVpnUiDelegate)
            runCurrent()
            assertEquals(ChangeServerViewState.Unlocked, expectMostRecentItem())
            changeServerManager.changeServer(mockVpnUiDelegate)
            runCurrent()
            assertEquals(ChangeServerViewState.Unlocked, expectMostRecentItem())
        }
    }

    @Test
    fun `change server cycles through available countries`() = testScope.runTest {
        updateVpnStatus(VpnState.Connected)
        changeServerManager.changeServer(mockVpnUiDelegate)
        runCurrent()
        changeServerManager.changeServer(mockVpnUiDelegate)
        runCurrent()

        assertEquals(listOf("SE", "DE"), connectedCountries)
    }

    @Test
    fun `change server skips countries without accessible servers`() = testScope.runTest {
        serversByCountry = serversByCountry + ("SE" to createServer("server-se-plus", exitCountry = "SE", tier = 2))
        updateVpnStatus(VpnState.Connected)
        changeServerManager.changeServer(mockVpnUiDelegate)
        runCurrent()

        assertEquals(listOf("DE"), connectedCountries)
    }

    @Test
    fun `updating to plus removes change server`() =
        testUpdatingToPlus(VpnState.Connected, ChangeServerViewState.Unlocked)

    @Test
    fun `updating to plus removes change server while connecting`() =
        testUpdatingToPlus(VpnState.Connecting, ChangeServerViewState.Disabled)

    private fun testUpdatingToPlus(
        vpnStateToCheck: VpnState,
        changeServerState: ChangeServerViewState
    ) = testScope.runTest {
        every { mockVpnConnectionManager.connect(any(), any(), any()) } answers {
            vpnStateMonitor.updateStatus(VpnStateMonitor.Status(vpnStateToCheck, connectionParams))
        }

        changeServerViewStateFlow.test {
            assertNull(awaitItem())
            changeServerManager.changeServer(mockVpnUiDelegate)
            assertEquals(changeServerState, awaitItem())
            testUserProvider.vpnUser = plusUser
            assertNull(awaitItem())
        }
    }

    @Test
    fun `when connecting takes long then change server appears`() = testScope.runTest {
        every { mockVpnConnectionManager.connect(any(), any(), any()) } answers {
            vpnStateMonitor.updateStatus(VpnStateMonitor.Status(VpnState.Connecting, connectionParams))
        }

        changeServerViewStateFlow.test {
            updateVpnStatus(VpnState.Connecting, ConnectIntent.FastestInCountry(CountryId.sweden, emptySet()))
            advanceTimeBy(LONG_CONNECT_TIME)
            assertEquals(ChangeServerViewState.Unlocked, expectMostRecentItem())
            changeServerManager.changeServer(mockVpnUiDelegate)
            assertEquals(ChangeServerViewState.Disabled, awaitItem()) // After server change.
            advanceTimeBy(LONG_CONNECT_TIME)
            assertEquals(ChangeServerViewState.Unlocked, awaitItem())
        }
    }

    @Test
    fun `waiting for network disables change server button`() = testScope.runTest {
        changeServerViewStateFlow.test {
            updateVpnStatus(VpnState.Connecting, ConnectIntent.Fastest)
            advanceTimeBy(LONG_CONNECT_TIME)
            assertEquals(ChangeServerViewState.Unlocked, expectMostRecentItem())
            updateVpnStatus(VpnState.WaitingForNetwork, ConnectIntent.Fastest)
            // make sure it doesn't get reenabled after timeout.
            advanceTimeBy(LONG_CONNECT_TIME)
            assertNull(expectMostRecentItem())
        }
    }

    @Test
    fun `ChangeServerViewState_Locked splits time in minutes and seconds`() {
        val state = ChangeServerViewState.Locked(90, 120, false)
        assertEquals(1, state.remainingTimeMinutes)
        assertEquals(30, state.remainingTimeSeconds)
    }

    private fun updateVpnStatus(vpnState: VpnState, intent: ConnectIntent = ConnectIntent.Fastest) {
        vpnStateMonitor.updateStatus(VpnStateMonitor.Status(vpnState, ConnectionParams(intent, server, null, null)))
    }

    private val connectedCountries = mutableListOf<String>()
}

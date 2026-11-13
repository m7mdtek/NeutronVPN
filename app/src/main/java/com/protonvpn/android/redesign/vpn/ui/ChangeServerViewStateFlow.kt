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

package com.protonvpn.android.redesign.vpn.ui

import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.redesign.vpn.ChangeServerManager
import com.protonvpn.android.vpn.VpnState
import com.protonvpn.android.vpn.VpnStatusProviderUI
import dagger.Reusable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

sealed class ChangeServerViewState {
    object Unlocked : ChangeServerViewState()
    object Disabled : ChangeServerViewState()
    data class Locked(
        val remainingTimeInSeconds: Int,
        val totalCooldownSeconds: Int,
        val isFullLocked: Boolean,
    ) : ChangeServerViewState() {
        val progress = remainingTimeInSeconds.toFloat() / totalCooldownSeconds
        val remainingTimeSeconds = remainingTimeInSeconds % 60
        val remainingTimeMinutes = remainingTimeInSeconds / 60
    }
}

@Reusable
class ChangeServerViewStateFlow @Inject constructor(
    vpnStateProviderUI: VpnStatusProviderUI,
    changeServerManager: ChangeServerManager,
    currentUser: CurrentUser,
): Flow<ChangeServerViewState?> {

    private val isFreeUser = currentUser.vpnUserFlow.map { it?.isFreeUser == true }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val freeUserChangeServerState: Flow<ChangeServerViewState?> =
        combine(
            vpnStateProviderUI.uiStatus,
            changeServerManager.isChangingServer,
            changeServerManager.hasTroubleConnecting,
        ) { vpnStatus, isChanging, hasTroubleConnecting ->
            // Combine to triple to be able to use flatMapLatest
            // Convert to combineTransformLatest if it gets implemented: https://github.com/Kotlin/kotlinx.coroutines/issues/1484
            Triple(vpnStatus, isChanging, hasTroubleConnecting)
        }.flatMapLatest { (vpnStatus, isChanging, hasTroubleConnecting) ->
            when {
                vpnStatus.state.isEstablishingConnection && hasTroubleConnecting ->
                    flowOf(ChangeServerViewState.Unlocked)
                vpnStatus.state.isEstablishingConnection && isChanging ->
                    flowOf(ChangeServerViewState.Disabled)
                vpnStatus.state is VpnState.Connected ->
                    flowOf(ChangeServerViewState.Unlocked)
                else ->
                    flowOf(null)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val stateFlow: Flow<ChangeServerViewState?> =
        isFreeUser.flatMapLatest { isFree ->
            if (isFree) freeUserChangeServerState else flowOf(null)
        }.distinctUntilChanged()

    override suspend fun collect(collector: FlowCollector<ChangeServerViewState?>) {
        stateFlow.collect(collector)
    }
}

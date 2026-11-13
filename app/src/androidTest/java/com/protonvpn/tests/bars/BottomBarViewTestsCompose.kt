package com.protonvpn.tests.bars

import com.protonvpn.android.R
import com.protonvpn.android.redesign.main_screen.ui.BottomBarView
import com.protonvpn.testRules.setVpnContent
import me.proton.test.fusion.Fusion.node
import me.proton.test.fusion.ui.compose.FusionComposeTest
import org.junit.Test

class BottomBarViewTestsCompose : FusionComposeTest() {

    @Test
    fun profileAndCountryNavigationItemsAreHidden() {
        composeRule.setVpnContent {
            BottomBarView(
                showGateways = true,
                selectedTarget = null,
                notificationDots = emptySet(),
                navigateTo = {},
            )
        }

        node.withText(R.string.bottom_nav_profiles).assertDoesNotExist()
        node.withText(R.string.bottom_nav_countries).assertDoesNotExist()
    }

    @Test
    fun whenShowGatewaysIsFalse_thenGatewaysNavigationBarItemIsHidden() {
        val showGateways = false

        composeRule.setVpnContent {
            BottomBarView(
                showGateways = showGateways,
                selectedTarget = null,
                notificationDots = emptySet(),
                navigateTo = {},
            )
        }

        node.withText(R.string.bottom_nav_gateways).assertDoesNotExist()
    }

    @Test
    fun whenShowGatewaysIsTrue_thenGatewaysNavigationBarItemIsShown() {
        val showGateways = true

        composeRule.setVpnContent {
            BottomBarView(
                showGateways = showGateways,
                selectedTarget = null,
                notificationDots = emptySet(),
                navigateTo = {},
            )
        }

        node.withText(R.string.bottom_nav_gateways).assertIsDisplayed()
    }

}

package com.tfitapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tfitapp.ui.food.FoodScreen
import com.tfitapp.ui.review.ReviewScreen
import com.tfitapp.ui.settings.SettingsScreen
import com.tfitapp.ui.theme.Rule
import com.tfitapp.ui.theme.TFit
import com.tfitapp.ui.theme.TFitTheme
import com.tfitapp.ui.timer.TimerScreen
import com.tfitapp.ui.train.TrainScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent { TFitTheme { TFitApp() } }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** The trend view lives under the Food tab, one tap away (design basis §4.5). */
private const val REVIEW_ROUTE = "food/review"

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    FOOD("food", "Food", Icons.Filled.Restaurant),
    TRAIN("train", "Train", Icons.Filled.FitnessCenter),
    TIMER("timer", "Timer", Icons.Filled.Timer),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@Composable
private fun TFitApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    val c = TFit.colors
    Scaffold(
        containerColor = c.concrete,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                Rule()
                NavigationBar(containerColor = c.chalk, tonalElevation = 0.dp) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route || it.route?.startsWith(tab.route + "/") == true } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c.iron, selectedTextColor = c.iron, indicatorColor = c.concrete,
                            unselectedIconColor = c.ironMuted, unselectedTextColor = c.ironMuted,
                        ),
                    )
                }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.FOOD.route, modifier = Modifier.padding(padding)) {
            composable(Tab.FOOD.route) { FoodScreen(onOpenReview = { nav.navigate(REVIEW_ROUTE) { launchSingleTop = true } }) }
            composable(REVIEW_ROUTE) { ReviewScreen(onBack = { nav.popBackStack() }) }
            composable(Tab.TRAIN.route) { TrainScreen(onOpenTimer = { nav.navigate(Tab.TIMER.route) { launchSingleTop = true } }) }
            composable(Tab.TIMER.route) { TimerScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
        }
    }
}

package com.olcayertas.tripsplit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.olcayertas.tripsplit.AppContainer
import com.olcayertas.tripsplit.data.identity.DeviceIdentity
import kotlinx.coroutines.launch

object Routes {
    const val GROUPS = "groups"
    const val CREATE = "create"
    const val NEARBY = "nearby"
    const val GROUP = "group/{groupId}"
    const val EXPENSE = "group/{groupId}/expense?expenseId={expenseId}"

    fun group(id: String) = "group/$id"
    fun expense(groupId: String, expenseId: String? = null) = "group/$groupId/expense?expenseId=${expenseId.orEmpty()}"
}

@Composable
fun Root(container: AppContainer) {
    var identity by remember { mutableStateOf<DeviceIdentity?>(null) }
    LaunchedEffect(container) { identity = container.identity.await() }
    val scope = rememberCoroutineScope()
    val current = identity
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (current.displayName.isBlank()) {
            FirstRunScreen(onDone = { name ->
                scope.launch {
                    container.identityManager.setDisplayName(name)
                    identity = current.copy(displayName = name)
                }
            })
        } else {
            AppNav(container, current)
        }
    }
}

@Composable
private fun AppNav(container: AppContainer, identity: DeviceIdentity) {
    val nav = rememberNavController()
    val repo = container.repository
    NavHost(nav, startDestination = Routes.GROUPS) {
        composable(Routes.GROUPS) {
            GroupsScreen(repo, identity.deviceId, onCreate = { nav.navigate(Routes.CREATE) }, onOpen = { nav.navigate(Routes.group(it)) })
        }
        composable(Routes.CREATE) {
            CreateGroupScreen(
                repo, identity.displayName,
                onBack = { nav.popBackStack() },
                onCreated = { id -> nav.navigate(Routes.group(id)) { popUpTo(Routes.GROUPS) } },
            )
        }
        composable(Routes.GROUP) { entry ->
            val groupId = entry.arguments!!.getString("groupId")!!
            GroupScreen(
                repo, groupId, identity,
                onBack = { nav.popBackStack() },
                onNearby = { nav.navigate(Routes.NEARBY) },
                onAddExpense = { nav.navigate(Routes.expense(groupId)) },
                onEditExpense = { nav.navigate(Routes.expense(groupId, it)) },
            )
        }
        composable(Routes.NEARBY) { NearbyScreen(container.nearby, onBack = { nav.popBackStack() }) }
        composable(Routes.EXPENSE) { entry ->
            val groupId = entry.arguments!!.getString("groupId")!!
            val expenseId = entry.arguments!!.getString("expenseId").orEmpty().ifEmpty { null }
            ExpenseRoute(repo, groupId, expenseId, onDone = { nav.popBackStack() })
        }
    }
}

package me.earzuchan.chatdrama.client.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.github.terrakok.navigation3.browser.HierarchicalBrowserNavigation

@Composable
internal actual fun BindRootBrowserNavigation(backStack: SnapshotStateList<RootRoute>) = HierarchicalBrowserNavigation()
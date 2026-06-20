package me.earzuchan.chatdrama.client.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList

@Composable
internal expect fun BindRootBrowserNavigation(backStack: SnapshotStateList<RootRoute>)

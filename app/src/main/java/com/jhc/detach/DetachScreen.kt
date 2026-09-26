package com.jhc.detach

import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetachScreen(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val ready = vm.loadState == LoadState.Ready

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        if (ready) {
                            Text(
                                "${vm.savedDetached.size} detached · ${vm.apps.size} apps",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (ready) {
                        IconButton(onClick = vm::load, enabled = !vm.applying) {
                            Icon(Icons.Filled.Refresh, "Reload")
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            ApplyBar(
                visible = ready && vm.pendingChanges > 0,
                changes = vm.pendingChanges,
                applying = vm.applying,
                onDiscard = vm::discard,
                onApply = vm::apply
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (val state = vm.loadState) {
            LoadState.Loading -> Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is LoadState.Error -> ErrorContent(state, vm::load, Modifier.padding(padding))
            LoadState.Ready -> AppsContent(vm, padding)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsContent(vm: MainViewModel, padding: PaddingValues) {
    val query = vm.query.trim().lowercase()
    val visible = vm.apps.filter { app ->
        (!vm.onlyDetached || app.packageName in vm.savedDetached || app.packageName in vm.detached) &&
                when (vm.appType) {
                    AppType.All -> true
                    AppType.User -> !app.system
                    AppType.System -> app.system
                } &&
                (query.isEmpty() || app.label.lowercase().contains(query) ||
                        app.packageName.lowercase().contains(query))
    }
    // Sections follow the applied state so rows don't jump around while toggling.
    val (detachedApps, otherApps) = visible.partition { it.packageName in vm.savedDetached }

    // Lazy lists keep their position by item key, which would leave the view parked on the
    // "Apps" header after a filter change; start from the top instead.
    val listState = rememberLazyListState()
    LaunchedEffect(vm.query, vm.onlyDetached, vm.appType) { listState.scrollToItem(0) }

    Column(Modifier.padding(top = padding.calculateTopPadding())) {
        SearchField(vm.query, onChange = { vm.query = it })
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterToggle("Detached only", vm.onlyDetached) { vm.onlyDetached = !vm.onlyDetached }
            FilterToggle("User", vm.appType == AppType.User) {
                vm.appType = if (vm.appType == AppType.User) AppType.All else AppType.User
            }
            FilterToggle("System", vm.appType == AppType.System) {
                vm.appType = if (vm.appType == AppType.System) AppType.All else AppType.System
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 8.dp)
        ) {
            if (detachedApps.isNotEmpty()) {
                stickyHeader(key = "h-detached") { SectionHeader("Detached", detachedApps.size) }
                items(detachedApps, key = { it.packageName }) { app ->
                    AppRow(app, app.packageName in vm.detached) { vm.toggle(app.packageName, it) }
                }
            }
            if (otherApps.isNotEmpty()) {
                stickyHeader(key = "h-apps") { SectionHeader("Apps", otherApps.size) }
                items(otherApps, key = { it.packageName }) { app ->
                    AppRow(app, app.packageName in vm.detached) { vm.toggle(app.packageName, it) }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (vm.query.isNotBlank()) "No apps match \"${vm.query.trim()}\""
                        else "No apps match these filters",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else null
    )
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange(""); focus.clearFocus() }) {
                    Icon(Icons.Filled.Close, "Clear search")
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title · $count",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onToggle),
        leadingContent = { AppIcon(app, 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!app.installed) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Not installed",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
        supportingContent = {
            Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private val iconCache = LruCache<String, ImageBitmap>(300)

@Composable
private fun AppIcon(app: AppEntry, size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val key = if (app.installed) app.packageName else "<uninstalled>"
    val icon by produceState(iconCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val drawable = try {
                if (app.installed) context.packageManager.getApplicationIcon(app.packageName)
                else null
            } catch (_: Exception) {
                null
            } ?: context.getDrawable(R.mipmap.unistalled_app)!!
            drawable.toBitmap(px, px).asImageBitmap().also { iconCache.put(key, it) }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    }
}

@Composable
private fun ApplyBar(
    visible: Boolean,
    changes: Int,
    applying: Boolean,
    onDiscard: () -> Unit,
    onApply: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Text(
                    "$changes unsaved change${if (changes == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDiscard, enabled = !applying) { Text("Discard") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApply, enabled = !applying) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(error: LoadState.Error, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Warning, null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.padding(8.dp))
        Text(error.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.padding(4.dp))
        Text(
            error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.padding(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

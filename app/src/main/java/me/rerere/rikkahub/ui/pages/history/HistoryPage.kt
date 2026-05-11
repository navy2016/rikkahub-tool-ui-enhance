package me.rerere.rikkahub.ui.pages.history;

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    val selectedConversationIds = remember { mutableStateListOf<Uuid>() }

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val selectionMode = selectedConversationIds.isNotEmpty()
    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)
    val selectedCountForMessage = selectedConversationIds.size
    val snackMessageBulkDeleted = stringResource(R.string.history_page_conversations_deleted, selectedCountForMessage)

    LaunchedEffect(conversations) {
        selectedConversationIds.removeAll { id -> conversations.none { it.id == id } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) {
                            stringResource(R.string.history_page_selected_count, selectedConversationIds.size)
                        } else {
                            stringResource(R.string.history_page_title)
                        }
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectedConversationIds.clear() }) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.history_page_cancel_selection))
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                selectedConversationIds.clear()
                                selectedConversationIds.addAll(conversations.map { it.id })
                            }
                        ) {
                            Text(stringResource(R.string.history_page_select_all))
                        }
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_selected))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.MessageSearch)
                            }
                        ) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages)
                            )
                        }
                        IconButton(
                            onClick = {
                                showDeleteAllDialog = true
                            }
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_all))
                        }
                    }
                },
                colors = if (selectionMode) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors()
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                val selected = conversation.id in selectedConversationIds
                SwipeableConversationItem(
                    conversation = conversation,
                    selectionMode = selectionMode,
                    selected = selected,
                    onClick = {
                        if (selectionMode) {
                            if (selected) selectedConversationIds.remove(conversation.id) else selectedConversationIds.add(conversation.id)
                        } else {
                            navigateToChatPage(navController, conversation.id)
                        }
                    },
                    onLongClick = {
                        if (!selected) selectedConversationIds.add(conversation.id)
                    },
                    onDelete = {
                        scope.launch {
                            // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                            val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                            vm.deleteConversation(conversation)
                            val result = snackbarHostState.showSnackbar(
                                message = snackMessageDeleted,
                                actionLabel = snackMessageUndo,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                vm.restoreConversation(fullConversation)
                            }
                        }
                    },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_selected)) },
            text = { Text(stringResource(R.string.history_page_delete_selected_confirmation, selectedConversationIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedConversationIds.toSet()
                        showDeleteSelectedDialog = false
                        selectedConversationIds.clear()
                        scope.launch {
                            val fullConversations = ids.mapNotNull { id -> vm.getFullConversation(id) }
                            vm.deleteConversations(fullConversations)
                            val result = snackbarHostState.showSnackbar(
                                message = snackMessageBulkDeleted,
                                actionLabel = snackMessageUndo,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                vm.restoreConversations(fullConversations)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(25)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !selectionMode,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            selectionMode = selectionMode,
            selected = selected,
            onTogglePin = onTogglePin,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Surface(
        tonalElevation = if (selected) 6.dp else 2.dp,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(25),
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
    ) {
        ListItem(
            leadingContent = if (selectionMode) {
                {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() }
                    )
                }
            } else null,
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = {
                IconButton(
                    onClick = onTogglePin
                ) {
                    Icon(
                        if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                        contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                            R.string.history_page_pin
                        )
                    )
                }
            }
        )
    }
}

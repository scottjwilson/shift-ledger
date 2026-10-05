package com.scott.shiftledger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
fun MessagesScreen(templates: List<MessageTemplate>, busy: Boolean, initialOrder: String,
    onSave: (String, String, String, () -> Unit) -> Unit, onDelete: (String, () -> Unit) -> Unit) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("list") }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(initialOrder) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var copied by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    BackHandler(page != "list") { page = "list"; copied = false }
    fun open(template: MessageTemplate?, destination: String) {
        selectedId = template?.id ?: UUID.randomUUID().toString()
        title = template?.title.orEmpty(); body = template?.body.orEmpty()
        order = initialOrder; custom = false; copied = false; page = destination
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("messages_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading(if (page == "list") "Saved messages" else if (page == "edit") "Edit template" else "Prepare message",
            if (page == "list") "Your reusable messages for Dispatch support." else if (page == "edit") "Save wording you use often." else "Check the wording, copy it, and paste into support.") }
        if (page == "list") {
            item { Button(onClick = { open(null, "edit") }, modifier = Modifier.fillMaxWidth()) { Text("+ New message") } }
            if (initialOrder.isNotBlank()) item { Hint("Order $initialOrder selected. Choose a message to fill in its order number.") }
            item { OutlinedTextField(search, { search = it }, label = { Text("Search messages") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            val visible = templates.filter { it.title.contains(search, true) || it.body.contains(search, true) }
            if (visible.isEmpty()) item { Text(if (templates.isEmpty()) "Save your first reusable support message." else "No messages match your search.") }
            items(visible, key = { it.id }) { template -> Panel {
                Text(template.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(template.body, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { open(template, "use") }) { Text("Use message") }
                    OutlinedButton(onClick = { open(template, "edit") }) { Text("Edit") }
                }
            } }
            item { Text("Saved messages are included in your full backup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        } else if (page == "edit") {
            item { OutlinedTextField(title, { title = it.take(100) }, label = { Text("Message title") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(body, { body = it.take(10_000) }, label = { Text("Template text") }, minLines = 6, modifier = Modifier.fillMaxWidth()) }
            item { Text("Use {order} wherever you want an order number. Example: Order #{order}.", style = MaterialTheme.typography.bodyMedium) }
            item { Button(onClick = { onSave(selectedId, title, body) { page = "list" } },
                enabled = !busy && title.isNotBlank() && body.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save message") } }
            item { TextButton(onClick = { page = "list" }, enabled = !busy) { Text("Cancel editing") } }
            if (templates.any { it.id == selectedId }) item { TextButton(onClick = { deleting = true }, enabled = !busy) { Text("Delete message", color = MaterialTheme.colorScheme.error) } }
        } else {
            item { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (!custom && body.contains(SupportMessages.ORDER_TOKEN)) item {
                OutlinedTextField(order, { order = it.take(40); copied = false }, label = { Text("Order number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            val rendered = if (custom) draft else if (order.isBlank()) body else SupportMessages.render(body, order)
            val valid = if (custom) draft.isNotBlank() && !draft.contains(SupportMessages.ORDER_TOKEN) else SupportMessages.canCopy(body, order)
            if (custom) item { OutlinedTextField(draft, { draft = it.take(10_000); copied = false }, label = { Text("Message to copy") }, minLines = 6, modifier = Modifier.fillMaxWidth()) }
            else item { Panel { SelectionContainer { Text(rendered) } } }
            if (!valid) item { Text("Fill in the order number before copying.", color = MaterialTheme.colorScheme.secondary) }
            item { Button(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, rendered))
                copied = true
            }, enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Copy message") } }
            if (copied) item { Text("Copied. Paste it into Dispatch support.", color = MaterialTheme.colorScheme.primary) }
            item { TextButton(onClick = {
                if (custom) custom = false else { draft = rendered; custom = true }
                copied = false
            }) { Text(if (custom) "Reset to template" else "Edit this copy") } }
            item { TextButton(onClick = { page = "list"; copied = false }) { Text("Back to messages") } }
        }
    }
    if (deleting) Confirm("Delete saved message?", title, "Delete", { deleting = false }) {
        onDelete(selectedId) { deleting = false; page = "list" }
    }
}

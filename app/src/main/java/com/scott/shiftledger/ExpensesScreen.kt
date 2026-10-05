package com.scott.shiftledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun ExpensesScreen(state: LedgerState, onAdd: () -> Unit, onVehicle: () -> Unit, onDelete: (Expense) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Expenses", "A quick record of what work costs you.") }
        item { OutlinedButton(onClick = onVehicle, modifier = Modifier.fillMaxWidth()) { Text("Fuel & maintenance · fill-ups and service") } }
        item { Panel { Metric("All recorded expenses", money(state.expenses.sumOf { it.amountCents })); Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ Add expense") } } }
        item { Text("Log the amount you paid. Dispatch reimbursements may already be included in your imported earnings; check before adding separate income.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        if (state.expenses.isEmpty()) item { Text("Fuel, tolls, parking, maintenance, and other costs will appear here.") }
        items(state.expenses, key = { it.id }) { expense -> Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(expense.category, fontWeight = FontWeight.Bold); Text(money(expense.amountCents), fontWeight = FontWeight.Bold) }
            Text(expense.date, style = MaterialTheme.typography.bodySmall)
            if (expense.note.isNotBlank()) Text(expense.note)
            if (state.fuelStops.any { it.expenseId == expense.id } || state.maintenance.any { it.expenseId == expense.id }) {
                Text("Linked vehicle cost. Edit or delete the source record to update this expense.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onVehicle) { Text("Manage vehicle record") }
            } else TextButton(onClick = { onDelete(expense) }) { Text("Delete") }
        } }
    }
}

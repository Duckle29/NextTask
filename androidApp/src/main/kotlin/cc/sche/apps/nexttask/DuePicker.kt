package cc.sche.apps.nexttask

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cc.sche.apps.nexttask.ical.ICalDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Asks for a due date, then for a time on it or the whole day. Starts from the task's current due date
 * ([current]), or today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuePickerDialog(current: TaskDate?, onConfirm: (ICalDate) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    // The chosen day, as an epoch day, once the first step is done.
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val day = pickedDay

    if (day == null) {
        val initial = when (current) {
            is TaskDate.AllDay -> current.date
            is TaskDate.At -> current.time.toLocalDate()
            null -> LocalDate.now(zone)
        }
        // The date picker works in UTC midnights.
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { state.selectedDateMillis?.let { pickedDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() } },
                    enabled = state.selectedDateMillis != null,
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state)
        }
    } else {
        val date = LocalDate.ofEpochDay(day)
        val initial = (current as? TaskDate.At)?.time?.toLocalTime() ?: DEFAULT_TIME
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("At a time, or all day?") },
            text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { onConfirm(dueDate(date, LocalTime.of(state.hour, state.minute), zone)) }) { Text("Set time") } },
            dismissButton = { TextButton(onClick = { onConfirm(dueDate(date, null, zone)) }) { Text("All day") } },
        )
    }
}

private val DEFAULT_TIME = LocalTime.of(9, 0)

package com.civiclauncher.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.civiclauncher.app.diag.ReportSection
import com.civiclauncher.app.diag.Row
import com.civiclauncher.app.diag.Status
import com.civiclauncher.app.diag.collect.hasPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(18.dp)

/** Pantalla estándar de una sección: título, botones y contenido desplazable. */
@Composable
fun SectionScaffold(
    title: String,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CivicColors.TextDim)
                }
            }
        }
        if (actions != null) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) { actions() }
            }
        }
        content()
    }
}

fun LazyListScope.sectionCard(section: ReportSection?) {
    item { if (section == null) LoadingCard() else InfoCard(section.rows, section.notes) }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(CivicColors.Surface, CardShape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) { content() }
}

@Composable
fun LoadingCard(text: String = "Leyendo…") {
    Card { Text(text, color = CivicColors.TextDim, modifier = Modifier.padding(vertical = 8.dp)) }
}

@Composable
fun InfoCard(rows: List<Row>, notes: List<String> = emptyList(), title: String? = null) {
    Card {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        SelectionContainer {
            Column {
                rows.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = CivicColors.Line, thickness = 1.dp)
                    InfoRow(r)
                }
            }
        }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            notes.forEach { Note(it) }
        }
    }
}

@Composable
fun InfoRow(row: Row) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(row.status)
        Spacer(Modifier.width(12.dp))
        Text(row.key, color = CivicColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.42f))
        Spacer(Modifier.width(12.dp))
        Text(row.value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.58f))
    }
}

fun statusColor(s: Status): Color = when (s) {
    Status.OK -> CivicColors.Ok
    Status.WARN -> CivicColors.Warn
    Status.BAD -> CivicColors.Bad
    Status.INFO -> CivicColors.Line
}

@Composable
fun StatusDot(status: Status) {
    Box(Modifier.size(10.dp).background(statusColor(status), CircleShape))
}

@Composable
fun Note(text: String) {
    Text(text, color = CivicColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
}

@Composable
fun MonoText(text: String) {
    SelectionContainer {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = CivicColors.Text)
    }
}

enum class ButtonKind { Primary, Secondary, Danger }

@Composable
fun ActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true, kind: ButtonKind = ButtonKind.Primary) {
    val colors = when (kind) {
        ButtonKind.Primary -> ButtonDefaults.buttonColors(containerColor = CivicColors.Accent, contentColor = Color.White)
        ButtonKind.Secondary -> ButtonDefaults.buttonColors(containerColor = CivicColors.Surface2, contentColor = CivicColors.Text)
        ButtonKind.Danger -> ButtonDefaults.buttonColors(containerColor = CivicColors.Bad, contentColor = Color.White)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 22.dp),
        modifier = Modifier.height(56.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Ejecuta [block] en segundo plano y devuelve su resultado. Conserva el valor anterior al refrescar. */
@Composable
fun <T> produceAsync(vararg keys: Any?, block: () -> T): T? {
    val state = produceState<T?>(initialValue = null, keys = keys) {
        val result = withContext(Dispatchers.IO) { runCatching(block).getOrNull() }
        value = result
    }
    return state.value
}

/** Devuelve una acción que pide [perms] si hace falta y luego ejecuta [onGranted]. */
@Composable
fun rememberPermissionAction(perms: Array<String>, proceedIfDenied: Boolean = false, onGranted: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ctx.hasPermissions(perms) || proceedIfDenied) current() else ctx.toast("Sin ese permiso no se puede hacer la prueba")
    }
    return {
        if (perms.isEmpty() || ctx.hasPermissions(perms)) current() else launcher.launch(perms)
    }
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

fun Context.startActivitySafe(intent: Intent): Boolean = runCatching {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrElse {
    toast("Esta pantalla no tiene esa opción")
    false
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

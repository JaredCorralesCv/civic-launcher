package com.civiclauncher.app.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.civiclauncher.app.container
import com.civiclauncher.app.launcher.data.LaunchableApp
import com.civiclauncher.app.ui.design.Civic

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val repo = ctx.container.apps
    val apps by repo.apps.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(top = Civic.space.m)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER), verticalAlignment = Alignment.Bottom) {
            Text("Apps", style = Civic.type.headline, color = Civic.colors.text)
            Spacer(Modifier.size(Civic.space.m))
            Text("${apps.size}", style = Civic.type.title, color = Civic.colors.textFaint)
            Spacer(Modifier.weight(1f))
            Text("Mantén presionada una app para ver su información", style = Civic.type.caption, color = Civic.colors.textFaint)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(136.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER, vertical = Civic.space.m),
            horizontalArrangement = Arrangement.spacedBy(Civic.space.m),
            verticalArrangement = Arrangement.spacedBy(Civic.space.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(apps, key = { "${it.pkg}/${it.activity}" }) { app ->
                AppCell(app, onClick = { repo.launch(app) }, onLongClick = { repo.openAppInfo(app.pkg) })
            }
        }
    }
}

@Composable
private fun AppCell(app: LaunchableApp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val repo = LocalContext.current.container.apps
    // El ícono se carga en segundo plano; mientras tanto se ve un círculo del color de la superficie.
    val icon by produceState<ImageBitmap?>(null, app) { value = repo.icon(app) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        Modifier
            .clip(RoundedCornerShape(Civic.radii.l))
            .background(if (pressed) Civic.colors.surfacePressed else Civic.colors.background)
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Civic.space.m, horizontal = Civic.space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            val bmp = icon
            if (bmp != null) {
                Image(bmp, contentDescription = null, modifier = Modifier.size(72.dp))
            } else {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(Civic.radii.l)).background(Civic.colors.surface))
            }
        }
        Spacer(Modifier.height(Civic.space.s))
        Text(
            app.label,
            style = Civic.type.bodySmall,
            color = Civic.colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

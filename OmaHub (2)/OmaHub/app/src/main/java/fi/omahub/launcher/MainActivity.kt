package fi.omahub.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Data ----------

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap, val intent: Intent)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("omahub", Context.MODE_PRIVATE)

    var favorites: List<String>
        get() = sp.getString("favs", "")!!.split(",").filter { it.isNotBlank() }
        set(v) = sp.edit().putString("favs", v.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    var wallpaper: Int
        get() = sp.getInt("wallpaper", 0)
        set(v) = sp.edit().putInt("wallpaper", v).apply()

    var tileSize: Int
        get() = sp.getInt("tileSize", 1)
        set(v) = sp.edit().putInt("tileSize", v).apply()
}

val TILE_WIDTHS = listOf(96.dp, 120.dp, 160.dp)
val TILE_NAMES = listOf("Pieni", "Normaali", "Suuri")

val WALLPAPERS = listOf(
    Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF1E3A8A))),
    Brush.linearGradient(listOf(Color(0xFF111111), Color(0xFF3B0764))),
    Brush.linearGradient(listOf(Color(0xFF052E16), Color(0xFF065F46))),
    Brush.linearGradient(listOf(Color(0xFF1F1F1F), Color(0xFF7F1D1D))),
    Brush.linearGradient(listOf(Color(0xFF000000), Color(0xFF262626))),
)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val isTv = (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
        .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val lean = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
    )
    val normal = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    )
    return (lean + normal)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != ctx.packageName }
        .mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            val intent = (if (isTv) {
                pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            } else {
                pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            }) ?: return@mapNotNull null
            AppItem(
                ri.loadLabel(pm).toString(),
                pkg,
                ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(),
                intent,
            )
        }
        .sortedBy { it.label.lowercase() }
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)
    private val homeSignal = mutableIntStateOf(0)

    // Päivittää sovelluslistan vain kun sovelluksia oikeasti asennetaan/poistetaan/päivitetään.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LauncherApp(refresh.intValue, homeSignal.intValue)
            }
        }
    }

    // Kotinäppäin: palaa aina päänäkymään (singleTask lähettää tänne uuden intentin).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeSignal.intValue++
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }
}

// ---------- UI ----------

@Composable
fun LauncherApp(refresh: Int, homeSignal: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    // Ladataan taustasäikeessä, ettei käynnistys jäädy.
    val allApps by produceState(emptyList<AppItem>(), refresh) {
        value = withContext(Dispatchers.Default) { loadApps(ctx) }
    }

    var favs by remember { mutableStateOf(prefs.favorites) }
    var hidden by remember { mutableStateOf(prefs.hidden) }
    var wallpaper by remember { mutableIntStateOf(prefs.wallpaper) }
    var tileSize by remember { mutableIntStateOf(prefs.tileSize) }
    var showSettings by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<AppItem?>(null) }

    LaunchedEffect(homeSignal) {
        showSettings = false
        menuApp = null
    }

    fun launch(a: AppItem) {
        try {
            ctx.startActivity(a.intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Sovellusta ei voitu avata", Toast.LENGTH_SHORT).show()
        }
    }

    val visible = allApps.filter { it.pkg !in hidden }
    val favApps = favs.mapNotNull { p -> visible.find { it.pkg == p } }

    Box(Modifier.fillMaxSize().background(WALLPAPERS[wallpaper]).statusBarsPadding().navigationBarsPadding()) {
        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(
                wallpaper = wallpaper,
                onWallpaper = { wallpaper = it; prefs.wallpaper = it },
                tileSize = tileSize,
                onTileSize = { tileSize = it; prefs.tileSize = it },
                hiddenApps = allApps.filter { it.pkg in hidden },
                onUnhide = { a -> hidden = hidden - a.pkg; prefs.hidden = hidden },
                onClose = { showSettings = false },
            )
        } else {
            HomeScreen(
                favorites = favApps,
                apps = visible,
                tileWidth = TILE_WIDTHS[tileSize.coerceIn(0, TILE_WIDTHS.lastIndex)],
                onOpen = ::launch,
                onMenu = { menuApp = it },
                onSettings = { showSettings = true },
            )
        }
    }

    menuApp?.let { app ->
        val isFav = app.pkg in favs
        AlertDialog(
            onDismissRequest = { menuApp = null },
            title = { Text(app.label) },
            text = {
                Column {
                    TextButton(onClick = {
                        favs = if (isFav) favs - app.pkg else favs + app.pkg
                        prefs.favorites = favs
                        menuApp = null
                    }) { Text(if (isFav) "Poista suosikeista" else "Lisää suosikkeihin") }
                    TextButton(onClick = {
                        hidden = hidden + app.pkg
                        prefs.hidden = hidden
                        menuApp = null
                    }) { Text("Piilota sovellus") }
                }
            },
            confirmButton = { TextButton(onClick = { menuApp = null }) { Text("Sulje") } },
        )
    }
}

@Composable
fun HomeScreen(
    favorites: List<AppItem>,
    apps: List<AppItem>,
    tileWidth: Dp,
    onOpen: (AppItem) -> Unit,
    onMenu: (AppItem) -> Unit,
    onSettings: () -> Unit,
) {
    val (hPad, vPad) = screenPadding()
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = hPad, vertical = vPad)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OmaHub", fontSize = 24.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Clock()
            Spacer(Modifier.width(16.dp))
            Button(onClick = onSettings) { Text("Asetukset") }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Hae sovellusta") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        if (favorites.isNotEmpty() && query.isBlank()) {
            SectionTitle("Suosikit")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(favorites, key = { it.pkg }) { app ->
                    AppTile(app, tileWidth, { onOpen(app) }, { onMenu(app) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        SectionTitle("Kaikki sovellukset")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tileWidth),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                AppTile(app, tileWidth, { onOpen(app) }, { onMenu(app) })
            }
        }
    }
}

/** Isompi reunus isoilla näytöillä (TV, tabletti), pienempi puhelimella. */
@Composable
fun screenPadding(): Pair<Dp, Dp> {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    return if (wide) 56.dp to 32.dp else 16.dp to 24.dp
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, color = Color(0xFFCBD5E1), modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEEE d.M.", Locale.getDefault()).format(now)
    Column(horizontalAlignment = Alignment.End) {
        Text(time, fontSize = 32.sp, color = Color.White)
        Text(date, fontSize = 14.sp, color = Color(0xFFCBD5E1))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(app: AppItem, tileWidth: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "scale")

    Column(
        modifier = Modifier
            .width(tileWidth)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(12.dp))
            .background(if (focused) Color(0x33FFFFFF) else Color(0x1AFFFFFF))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(app.icon, contentDescription = app.label, modifier = Modifier.size(tileWidth * 0.53f))
        Spacer(Modifier.height(6.dp))
        Text(
            app.label,
            color = Color.White,
            fontSize = (tileWidth.value / 9f).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SettingsScreen(
    wallpaper: Int,
    onWallpaper: (Int) -> Unit,
    tileSize: Int,
    onTileSize: (Int) -> Unit,
    hiddenApps: List<AppItem>,
    onUnhide: (AppItem) -> Unit,
    onClose: () -> Unit,
) {
    val (hPad, vPad) = screenPadding()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = hPad, vertical = vPad),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Asetukset", fontSize = 28.sp, color = Color.White)
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose) { Text("Takaisin") }
            }
        }
        item { SectionTitle("Tausta") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                items(WALLPAPERS.size) { i -> Swatch(WALLPAPERS[i], selected = i == wallpaper) { onWallpaper(i) } }
            }
        }
        item { SectionTitle("Ruudukon koko") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TILE_NAMES.forEachIndexed { i, name ->
                    if (i == tileSize) Button(onClick = { onTileSize(i) }) { Text(name) }
                    else OutlinedButton(onClick = { onTileSize(i) }) { Text(name) }
                }
            }
        }
        item { SectionTitle("Piilotetut sovellukset") }
        if (hiddenApps.isEmpty()) {
            item { Text("Ei piilotettuja sovelluksia.", color = Color(0xFFCBD5E1)) }
        } else {
            items(hiddenApps, key = { it.pkg }) { app ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, color = Color.White, modifier = Modifier.weight(1f))
                    Button(onClick = { onUnhide(app) }) { Text("Näytä") }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "OmaHub ei kerää tietoja, ei näytä mainoksia eikä sisällä maksullisia ominaisuuksia.",
                color = Color(0xFF94A3B8), fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun Swatch(brush: Brush, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(72.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(brush)
            .border(
                if (focused || selected) 3.dp else 1.dp,
                if (focused) Color.White else if (selected) Color(0xFF93C5FD) else Color(0x55FFFFFF),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
    )
}

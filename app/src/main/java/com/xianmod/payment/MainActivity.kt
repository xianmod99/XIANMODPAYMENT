package com.xianmod.payment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianmod.payment.listener.GoPayNotificationListener
import com.xianmod.payment.ui.theme.XianmodTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

private val Bg = Color(0xFF100D18)
private val Panel = Color(0xFF211A30)
private val Violet = Color(0xFF9B72F2)
private val Muted = Color(0xFFB9AEC9)
private val Green = Color(0xFF55D69E)

class MainActivity : ComponentActivity() {
    private val notificationAccessGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshNotificationAccess()
        setContent {
            XianmodTheme {
                PaymentApp(
                    notificationAccessGranted = notificationAccessGranted.value,
                    onOpenNotificationSettings = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationAccess()
    }

    private fun refreshNotificationAccess() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val expected = ComponentName(this, GoPayNotificationListener::class.java)
        notificationAccessGranted.value = enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }
}

@Composable
private fun PaymentApp(notificationAccessGranted: Boolean, onOpenNotificationSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(0) }
    var listenerConnected by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(DeviceTokenStore.token(context) != null) }
    var deviceId by remember { mutableStateOf(DeviceTokenStore.deviceId(context).orEmpty()) }
    var pendingOrders by remember { mutableStateOf<List<PaymentApi.PendingOrder>>(emptyList()) }
    var serverState by remember { mutableStateOf(if (paired) "MENYAMBUNG" else "BELUM DIPASANGKAN") }
    var lastError by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var pairingBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            listenerConnected = context.getSharedPreferences(GoPayNotificationListener.PREFS, Context.MODE_PRIVATE)
                .getBoolean(GoPayNotificationListener.KEY_CONNECTED, false)
            delay(1200)
        }
    }

    LaunchedEffect(paired) {
        if (paired) {
            while (true) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val orders = PaymentApi.fetchOrders(context)
                        PaymentApi.heartbeat(context, listenerConnected)
                        orders
                    }
                    pendingOrders = result
                    serverState = "TERHUBUNG"
                    lastError = ""
                } catch (e: Exception) {
                    serverState = "OFFLINE / TOKEN INVALID"
                    lastError = e.message ?: "Koneksi gagal"
                }
                delay(10_000)
            }
        } else {
            pendingOrders = emptyList()
            serverState = "BELUM DIPASANGKAN"
        }
    }

    val labels = listOf("Beranda", "Order", "Monitor", "Pengaturan")
    val icons = listOf(Icons.Default.Dashboard, Icons.Default.ReceiptLong, Icons.Default.Radar, Icons.Default.Settings)

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF191423), contentColor = Violet) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(icons[index], contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = Color.White,
                            indicatorColor = Violet.copy(alpha = .30f),
                            unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            0 -> DashboardScreen(Modifier.padding(padding), notificationAccessGranted, listenerConnected, paired, serverState, pendingOrders, lastError, onOpenNotificationSettings)
            1 -> OrdersScreen(Modifier.padding(padding), pendingOrders, paired, serverState)
            2 -> MonitorScreen(Modifier.padding(padding), notificationAccessGranted, listenerConnected, onOpenNotificationSettings)
            else -> SettingsScreen(
                modifier = Modifier.padding(padding), onOpenSettings = onOpenNotificationSettings,
                paired = paired, deviceId = deviceId, serverState = serverState,
                pairCode = pairCode, onPairCodeChange = { pairCode = it }, pairingBusy = pairingBusy,
                message = lastError, onPair = {
                    if (!pairingBusy && pairCode.isNotBlank()) {
                        pairingBusy = true
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) { PaymentApi.pair(context, pairCode.trim()) }
                                DeviceTokenStore.save(context, result.token, result.deviceId)
                                deviceId = result.deviceId
                                paired = true
                                pairCode = ""
                                lastError = "Perangkat berhasil dipasangkan."
                            } catch (e: Exception) {
                                lastError = e.message ?: "Pairing gagal"
                            } finally { pairingBusy = false }
                        }
                    }
                }, onUnpair = {
                    DeviceTokenStore.clear(context)
                    paired = false
                    deviceId = ""
                    lastError = "Token perangkat dihapus dari HP. Owner tetap perlu mencabut device di server jika perangkat hilang."
                }
            )
        }
    }
}

@Composable
private fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF191326), Bg))),
        contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.Image(painterResource(R.drawable.xianmod_logo), "Logo XIANMOD", Modifier.size(46.dp))
                Column {
                    Text("XIANMOD", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Text("PAYMENT COMPANION", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Column(content = content) }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Panel.copy(alpha = .88f), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .09f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun DashboardScreen(
    modifier: Modifier, notificationAccessGranted: Boolean, listenerConnected: Boolean,
    paired: Boolean, serverState: String, orders: List<PaymentApi.PendingOrder>, error: String,
    onOpenSettings: () -> Unit
) {
    Page(modifier) {
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("STATUS LISTENER", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                StatusPill(if (!notificationAccessGranted) "PERLU IZIN" else if (listenerConnected) "AKTIF" else "MENUNGGU", if (!notificationAccessGranted) Color(0xFFFFB74D) else if (listenerConnected) Green else Violet)
            }
            Text(if (!notificationAccessGranted) "Akses notifikasi belum aktif" else if (listenerConnected) "Listener Android aktif" else "Menghubungkan listener", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("Aktifkan akses notifikasi untuk menerima notifikasi aplikasi merchant yang dikonfigurasi.", color = Muted, fontSize = 12.sp)
            Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text("Notification Access")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Order pending", if (paired) orders.size.toString() else "—", Modifier.weight(1f))
            MetricCard("API server", serverState, Modifier.weight(1f))
        }
        GlassCard {
            Text("Koneksi perangkat", color = Color.White, fontWeight = FontWeight.Bold)
            Text(if (paired) "Perangkat sudah terhubung ke XIANMOD API." else "Pasangkan perangkat melalui menu Pengaturan menggunakan kode dari Owner Panel.", color = Muted, fontSize = 12.sp)
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFB74D), fontSize = 11.sp)
        }
        GlassCard {
            Text("Order terbaru", color = Color.White, fontWeight = FontWeight.Bold)
            if (!paired) Text("Pasangkan perangkat untuk memuat order PENDING.", color = Muted, fontSize = 12.sp)
            else if (orders.isEmpty()) Text("Tidak ada order PENDING saat ini.", color = Muted, fontSize = 12.sp)
            else orders.take(4).forEach { OrderRow(it) }
        }
        GlassCard {
            Text("Kesehatan perangkat", color = Color.White, fontWeight = FontWeight.Bold)
            InfoRow("Model", android.os.Build.MODEL ?: "Android")
            InfoRow("Android", android.os.Build.VERSION.RELEASE ?: "Unknown")
            InfoRow("Mode", "MANUAL OWNER REVIEW")
        }
    }
}

@Composable
private fun OrdersScreen(modifier: Modifier, orders: List<PaymentApi.PendingOrder>, paired: Boolean, serverState: String) {
    Page(modifier) {
        GlassCard {
            Text("Order PENDING", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Daftar diperbarui otomatis setiap 10 detik melalui API perangkat yang terautentikasi.", color = Muted, fontSize = 12.sp)
            StatusPill(if (paired) serverState else "PAIRING DIPERLUKAN", if (paired && serverState == "TERHUBUNG") Green else Muted)
        }
        if (!paired) GlassCard { Text("Pasangkan perangkat di Pengaturan untuk melihat order.", color = Muted) }
        else if (orders.isEmpty()) GlassCard { Text("Belum ada order PENDING.", color = Muted) }
        else orders.forEach { GlassCard { OrderRow(it) } }
    }
}

@Composable
private fun OrderRow(order: PaymentApi.PendingOrder) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(order.orderId, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            StatusPill("PENDING", Color(0xFFFFB74D))
        }
        Text(order.packageName, color = Muted, fontSize = 12.sp)
        Text("Rp ${NumberFormat.getIntegerInstance(Locale("id", "ID")).format(order.amount)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("QR expires: ${order.expiresAt.ifBlank { "-" }}", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun MonitorScreen(modifier: Modifier, access: Boolean, connected: Boolean, onOpenSettings: () -> Unit) {
    Page(modifier) {
        GlassCard {
            Text("Live notification monitor", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Notifikasi hanya diproses jika package merchant dan parser pembayaran telah diverifikasi.", color = Muted, fontSize = 12.sp)
            StatusPill(if (connected) "LISTENER AKTIF" else "LISTENER OFFLINE", if (connected) Green else Muted)
            Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Violet)) { Text(if (access) "Kelola izin" else "Aktifkan Notification Access") }
        }
        GlassCard {
            Text("Kebijakan keamanan", color = Color.White, fontWeight = FontWeight.Bold)
            Text("APK tidak pernah menandai order PAID. Semua event notifikasi masuk ke backend sebagai kandidat dan menunggu pemeriksaan Owner.", color = Muted, fontSize = 12.sp)
            Text("Parser GoPay masih dinonaktifkan sampai format notifikasi resmi diverifikasi.", color = Color(0xFFFFB74D), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier, onOpenSettings: () -> Unit, paired: Boolean, deviceId: String,
    serverState: String, pairCode: String, onPairCodeChange: (String) -> Unit,
    pairingBusy: Boolean, message: String, onPair: () -> Unit, onUnpair: () -> Unit
) {
    Page(modifier) {
        GlassCard {
            Text("Pengaturan perangkat", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Server: ${BuildConfig.API_BASE_URL}", color = Muted, fontSize = 11.sp)
            Text("Status API: $serverState", color = Color.White, fontSize = 12.sp)
            if (paired) {
                Text("Device ID", color = Muted, fontSize = 11.sp)
                Text(deviceId, color = Color.White, fontSize = 11.sp)
                OutlinedButton(onClick = onUnpair, modifier = Modifier.fillMaxWidth()) { Text("Putuskan perangkat dari HP") }
            } else {
                OutlinedTextField(value = pairCode, onValueChange = onPairCodeChange, label = { Text("Kode pairing dari Owner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = onPair, enabled = !pairingBusy && pairCode.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                    Text(if (pairingBusy) "Menghubungkan..." else "Hubungkan perangkat")
                }
            }
            if (message.isNotBlank()) Text(message, color = Color(0xFFFFB74D), fontSize = 11.sp)
            Divider(color = Color.White.copy(alpha = .08f))
            Text("Notification Access", color = Color.White, fontWeight = FontWeight.Medium)
            Text("Buka pengaturan Android untuk mengaktifkan layanan pembaca notifikasi.", color = Muted, fontSize = 11.sp)
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Buka Notification Access") }
        }
        GlassCard {
            Text("Keamanan", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Token perangkat disimpan terenkripsi menggunakan Android Keystore. Token asli tidak dikirim ke tabel database; server hanya menyimpan hash.", color = Muted, fontSize = 12.sp)
            Text("Jika perangkat hilang, cabut device melalui Owner Panel / endpoint revoke.", color = Muted, fontSize = 11.sp)
        }
        GlassCard {
            Text("Tentang aplikasi", color = Color.White, fontWeight = FontWeight.Bold)
            Text("XIANMOD PAYMENT v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel.copy(alpha = .9f), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Muted, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = .15f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp)
    }
}

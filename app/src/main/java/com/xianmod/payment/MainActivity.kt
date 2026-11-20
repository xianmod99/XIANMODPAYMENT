package com.xianmod.payment

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CancellationException
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
    private val postNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshNotificationAccess()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            postNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        if (DeviceTokenStore.token(this) != null) OrderWatcherService.start(this)
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
    var approveTarget by remember { mutableStateOf<PaymentApi.PendingOrder?>(null) }
    var approveBusy by remember { mutableStateOf(false) }
    var watchEnabled by remember { mutableStateOf(OrderWatcherService.isEnabled(context)) }
    var approveMessage by remember { mutableStateOf("") }

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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val rejected = e is ApiException && e.httpStatus == 401
                    serverState = if (rejected) "TOKEN DITOLAK" else "OFFLINE"
                    lastError = PaymentErrors.describe(e)
                }
                delay(5_000)
            }
        } else {
            pendingOrders = emptyList()
            serverState = "BELUM DIPASANGKAN"
        }
    }

    val labels = listOf("Beranda", "Order", "Monitor", "Pengaturan")
    val icons = listOf(Icons.Default.Dashboard, Icons.Default.ReceiptLong, Icons.Default.Radar, Icons.Default.Settings)

    approveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!approveBusy) approveTarget = null },
            title = { Text("Setujui pembayaran manual?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Order: ${target.orderId}")
                    Text("Paket: ${target.packageName}")
                    Text("Nominal: Rp ${NumberFormat.getIntegerInstance(Locale("id", "ID")).format(target.amount)}")
                    Text("Pastikan uang dengan nominal PERSIS ini sudah masuk di GoPay Merchant. Setelah disetujui, order jadi PAID dan VIP dikirim ke pembeli.")
                }
            },
            confirmButton = {
                Button(
                    enabled = !approveBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black),
                    onClick = {
                        approveBusy = true
                        scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) { PaymentApi.approveOrder(context, target.orderId, target.amount) }
                                approveMessage = when {
                                    r.already -> "Order ${target.orderId} sudah PAID sebelumnya."
                                    r.fulfillmentOk -> "Order ${target.orderId} disetujui dan VIP terkirim."
                                    else -> "Order ${target.orderId} disetujui (PAID), tetapi pengiriman VIP perlu dicek Owner: ${r.fulfillmentStatus ?: "-"} ${r.fulfillmentError.orEmpty()}".trim()
                                }
                                pendingOrders = pendingOrders.filter { it.orderId != target.orderId }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                approveMessage = PaymentErrors.describe(e)
                            } finally {
                                approveBusy = false
                                approveTarget = null
                            }
                        }
                    }
                ) { Text(if (approveBusy) "Memproses..." else "Ya, setujui") }
            },
            dismissButton = { TextButton(enabled = !approveBusy, onClick = { approveTarget = null }) { Text("Batal") } }
        )
    }

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
            1 -> OrdersScreen(Modifier.padding(padding), pendingOrders, paired, serverState, approveMessage) { approveTarget = it }
            2 -> MonitorScreen(Modifier.padding(padding), notificationAccessGranted, listenerConnected, onOpenNotificationSettings)
            else -> SettingsScreen(
                modifier = Modifier.padding(padding), onOpenSettings = onOpenNotificationSettings,
                paired = paired, deviceId = deviceId, serverState = serverState,
                watchEnabled = watchEnabled,
                onWatchChange = { on ->
                    OrderWatcherService.setEnabled(context, on)
                    watchEnabled = on
                    if (on && paired) OrderWatcherService.start(context) else OrderWatcherService.stop(context)
                },
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
                                OrderWatcherService.start(context)
                                pairCode = ""
                                lastError = "Perangkat berhasil dipasangkan."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                lastError = PaymentErrors.describe(e)
                            } finally { pairingBusy = false }
                        }
                    }
                }, onUnpair = {
                    OrderWatcherService.stop(context)
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
            InfoRow("Mode", "APPROVE MANUAL OWNER")
        }
    }
}

@Composable
private fun OrdersScreen(modifier: Modifier, orders: List<PaymentApi.PendingOrder>, paired: Boolean, serverState: String, message: String, onApprove: (PaymentApi.PendingOrder) -> Unit) {
    Page(modifier) {
        GlassCard {
            Text("Order PENDING", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Daftar disinkronkan setiap 5 detik melalui API perangkat yang terautentikasi (polling, bukan push realtime).", color = Muted, fontSize = 12.sp)
            StatusPill(if (paired) serverState else "PAIRING DIPERLUKAN", if (paired && serverState == "TERHUBUNG") Green else Muted)
            Text("Notifikasi GoPay tidak masuk? Cek uang di GoPay Merchant, lalu setujui manual lewat tombol di bawah tiap order.", color = Muted, fontSize = 11.sp)
            if (message.isNotBlank()) Text(message, color = Color(0xFFFFB74D), fontSize = 12.sp)
        }
        if (!paired) GlassCard { Text("Pasangkan perangkat di Pengaturan untuk melihat order.", color = Muted) }
        else if (orders.isEmpty()) GlassCard { Text("Belum ada order PENDING.", color = Muted) }
        else orders.forEach { order -> GlassCard { OrderRow(order, onApprove = { onApprove(order) }) } }
    }
}

@Composable
private fun OrderRow(order: PaymentApi.PendingOrder, onApprove: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(order.orderId, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            StatusPill("PENDING", Color(0xFFFFB74D))
        }
        Text(order.packageName, color = Muted, fontSize = 12.sp)
        Text("Rp ${NumberFormat.getIntegerInstance(Locale("id", "ID")).format(order.amount)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("QR expires: ${order.expiresAt.ifBlank { "-" }}", color = Muted, fontSize = 10.sp)
        if (onApprove != null) {
            Button(onClick = onApprove, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)) {
                Text("Setujui manual (sudah terima uang)", fontWeight = FontWeight.Bold)
            }
        }
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
    pairingBusy: Boolean, message: String, onPair: () -> Unit, onUnpair: () -> Unit,
    watchEnabled: Boolean, onWatchChange: (Boolean) -> Unit
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Notifikasi order baru", color = Color.White, fontWeight = FontWeight.Medium)
                    Text("Muncul di HP saat ada order PENDING masuk, walau aplikasi ditutup.", color = Muted, fontSize = 11.sp)
                }
                Switch(checked = watchEnabled, onCheckedChange = onWatchChange)
            }
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

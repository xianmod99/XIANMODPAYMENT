package com.xianmod.payment

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianmod.payment.ui.theme.XianmodTheme

private val Bg = Color(0xFF100D18)
private val Panel = Color(0xFF211A30)
private val Violet = Color(0xFF9B72F2)
private val Muted = Color(0xFFB9AEC9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XianmodTheme {
                PaymentApp(
                    onOpenNotificationSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }
}

@Composable
private fun PaymentApp(onOpenNotificationSettings: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val labels = listOf("Beranda", "Transaksi", "Monitor", "Pengaturan")
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
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Violet.copy(alpha = .30f),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            0 -> DashboardScreen(Modifier.padding(padding), onOpenNotificationSettings)
            1 -> TransactionsScreen(Modifier.padding(padding))
            2 -> MonitorScreen(Modifier.padding(padding), onOpenNotificationSettings)
            else -> SettingsScreen(Modifier.padding(padding), onOpenNotificationSettings)
        }
    }
}

@Composable
private fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF191326), Bg))
        ),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("XIANMOD", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text("PAYMENT COMPANION", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Column(content = content) }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Panel.copy(alpha = .88f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .09f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun DashboardScreen(modifier: Modifier, onOpenSettings: () -> Unit) {
    Page(modifier) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("STATUS LISTENER", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                StatusPill("PERLU IZIN", Color(0xFFFFB74D))
            }
            Text("Belum terhubung", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Aktifkan Notification Access untuk mulai menerima event merchant.", color = Muted, fontSize = 12.sp)
            Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                Icon(Icons.Default.NotificationsActive, null)
                Spacer(Modifier.width(8.dp))
                Text("Buka Notification Access")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Transaksi", "—", Modifier.weight(1f))
            MetricCard("Matched", "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Perlu review", "—", Modifier.weight(1f))
            MetricCard("Event antrean", "0", Modifier.weight(1f))
        }
        GlassCard {
            Text("Aktivitas terbaru", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Belum ada event pembayaran.", color = Muted, fontSize = 12.sp)
            Text("Event akan muncul setelah notifikasi merchant diterima dan diproses.", color = Muted, fontSize = 11.sp)
        }
        GlassCard {
            Text("Kesehatan perangkat", color = Color.White, fontWeight = FontWeight.Bold)
            InfoRow("Perangkat", android.os.Build.MODEL ?: "Android")
            InfoRow("Android", android.os.Build.VERSION.RELEASE ?: "Unknown")
            InfoRow("Mode pembayaran", "SAFE / REVIEW REQUIRED")
        }
    }
}

@Composable
private fun TransactionsScreen(modifier: Modifier) {
    Page(modifier) {
        GlassCard {
            Text("Transaksi", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Riwayat transaksi akan dimuat dari backend setelah konfigurasi API.", color = Muted, fontSize = 12.sp)
            StatusPill("BELUM ADA DATA", Muted)
        }
        GlassCard {
            Text("Filter", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Semua · Pending · Matched · Manual review · Ditolak", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MonitorScreen(modifier: Modifier, onOpenSettings: () -> Unit) {
    Page(modifier) {
        GlassCard {
            Text("Live notification monitor", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Listener menunggu notifikasi dari aplikasi merchant yang telah diverifikasi.", color = Muted, fontSize = 12.sp)
            Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                Text("Periksa izin listener")
            }
        }
        GlassCard {
            Text("Event stream", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Belum ada event.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, onOpenSettings: () -> Unit) {
    Page(modifier) {
        GlassCard {
            Text("Pengaturan", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            SettingsRow("Notification Access", "Buka pengaturan Android", onOpenSettings)
            Divider(color = Color.White.copy(alpha = .08f))
            SettingsRow("Koneksi server", "Belum dikonfigurasi") {}
            Divider(color = Color.White.copy(alpha = .08f))
            SettingsRow("Export logs", "Akan tersedia pada tahap berikutnya") {}
            Divider(color = Color.White.copy(alpha = .08f))
            SettingsRow("Device health", "Lihat status perangkat") {}
        }
        GlassCard {
            Text("Tentang aplikasi", color = Color.White, fontWeight = FontWeight.Bold)
            Text("XIANMOD PAYMENT v1.0.0", color = Muted, fontSize = 12.sp)
            Text("Build awal — payment auto-activation belum diaktifkan.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel.copy(alpha = .9f), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Muted, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color.copy(alpha = .15f), shape = RoundedCornerShape(50)) {
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

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(vertical = 5.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

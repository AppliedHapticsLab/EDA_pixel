package com.example.eda_receiver

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

// --- データモデル ---
data class EdaFile(
    val id: Long, val name: String, val uri: Uri, val dateAdded: Long, val size: Long
)

class MainActivity : ComponentActivity() {

    // CSV受信コールバック
    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            super.onChannelOpened(channel)
            if (channel.path == "/eda_csv") receiveFile(channel)
        }
    }

    // リアルタイムデータ受信コールバック
    var onRealTimeDataReceived: ((Float) -> Unit)? = null

    private val messageListener = MessageClient.OnMessageReceivedListener { messageEvent ->
        if (messageEvent.path == "/realtime_eda") {
            val value = ByteBuffer.wrap(messageEvent.data).float
            onRealTimeDataReceived?.invoke(value)
        }
    }

    private var shouldRefreshCsv = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            EdaAppTheme {
                MainScreen(
                    refreshCsvTrigger = shouldRefreshCsv.value,
                    registerRealTimeListener = { callback ->
                        onRealTimeDataReceived = callback
                    },
                    onSendCommand = { command -> sendCommandToWatch(command) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getChannelClient(this).registerChannelCallback(channelCallback)
        Wearable.getMessageClient(this).addListener(messageListener)
        shouldRefreshCsv.value = !shouldRefreshCsv.value
    }

    override fun onPause() {
        super.onPause()
        Wearable.getChannelClient(this).unregisterChannelCallback(channelCallback)
        Wearable.getMessageClient(this).removeListener(messageListener)
    }

    // Watchへコマンド送信
    private fun sendCommandToWatch(path: String) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(applicationContext).connectedNodes)
                nodes.forEach { node ->
                    Tasks.await(Wearable.getMessageClient(applicationContext).sendMessage(node.id, path, ByteArray(0)))
                }
                withContext(Dispatchers.Main) {
                    val msg = if(path.contains("start")) "Start Signal Sent" else "Stop Signal Sent"
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Sender", "Failed to send command", e)
            }
        }
    }

    private fun receiveFile(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val inputStream = client.getInputStream(channel).await()
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "EDA_Received_$timeStamp.csv"

                if (saveToDownloads(inputStream, fileName)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Received: $fileName", Toast.LENGTH_LONG).show()
                        shouldRefreshCsv.value = !shouldRefreshCsv.value
                    }
                }
                inputStream.close()
                client.close(channel)
            } catch (e: Exception) {
                Log.e("Receiver", "Error", e)
            }
        }
    }

    private fun saveToDownloads(inputStream: InputStream, fileName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { inputStream.copyTo(it) }
            true
        } catch (e: Exception) { false }
    }
}

// --- UI Components ---

@Composable
fun EdaAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Color(0xFFF2F2F7), surface = Color.White, primary = Color(0xFF007AFF),
            onBackground = Color.Black, onSurface = Color.Black
        ), content = content
    )
}

@Composable
fun MainScreen(
    refreshCsvTrigger: Boolean,
    registerRealTimeListener: ((Float) -> Unit) -> Unit,
    onSendCommand: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Source, contentDescription = "Files") },
                    label = { Text("Files") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF007AFF), indicatorColor = Color(0xFFE5E5EA))
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.MonitorHeart, contentDescription = "Monitor") },
                    label = { Text("Monitor") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF007AFF), indicatorColor = Color(0xFFE5E5EA))
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                FileListFlow(refreshCsvTrigger)
            } else {
                RealTimeMonitorScreen(registerRealTimeListener, onSendCommand)
            }
        }
    }
}

// --- Tab 1: File List & Viewer ---
@Composable
fun FileListFlow(refreshTrigger: Boolean) {
    var selectedFile by remember { mutableStateOf<EdaFile?>(null) }
    val context = LocalContext.current

    if (selectedFile == null) {
        EdaListScreen(refreshTrigger = refreshTrigger, onFileClick = { selectedFile = it })
    } else {
        BackHandler { selectedFile = null }
        CsvViewerScreen(
            file = selectedFile!!,
            onBack = { selectedFile = null },
            onShare = { shareFile(context, selectedFile!!.uri) }
        )
    }
}

// --- Tab 2: Realtime Monitor ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealTimeMonitorScreen(
    registerListener: ((Float) -> Unit) -> Unit,
    onSendCommand: (String) -> Unit
) {
    val dataPoints = remember { mutableStateListOf<Float>() }
    var currentValue by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        registerListener { value ->
            currentValue = value
            dataPoints.add(value)
            // データ点数を200くらいまで増やして滑らかに
            if (dataPoints.size > 200) dataPoints.removeAt(0)
        }
        onDispose { registerListener {} }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Real-time EDA", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Spacer(modifier = Modifier.height(16.dp))

        // 数値表示
        Text(
            text = String.format("%.2f µS", currentValue),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF007AFF)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // グラフエリア (weightを使って残りスペースを埋める)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // ここで高さを自動調整
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            if (dataPoints.isEmpty()) {
                Text(
                    "Waiting for data...",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Gray
                )
            } else {
                LineChart(dataPoints)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ボタンエリア
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Start Button
            Button(
                onClick = { onSendCommand("/start_recording") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE25142)), // Google Red
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).padding(end = 8.dp).fillMaxHeight()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            // Stop Button
            Button(
                onClick = { onSendCommand("/stop_recording") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)), // Dark Gray
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).padding(start = 8.dp).fillMaxHeight()
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// 軸と目盛り付きの折れ線グラフ (完全版)
@Composable
fun LineChart(data: List<Float>) {
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.DKGRAY
            textSize = 32f
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 36f
            textAlign = android.graphics.Paint.Align.LEFT
            isFakeBoldText = true
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // 軸描画用にパディングを確保 (上部を広めに)
            .padding(start = 50.dp, bottom = 30.dp, top = 40.dp, end = 20.dp)
    ) {
        if (data.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height

        val maxDataVal = data.maxOrNull() ?: 1f
        val maxY = (maxDataVal * 1.2f).coerceAtLeast(1f)
        val minY = 0f

        // 1. グリッド線と数値
        val steps = 5
        for (i in 0..steps) {
            val yRatio = i.toFloat() / steps
            val yPos = height - (yRatio * height)
            val value = minY + (yRatio * (maxY - minY))

            // 横グリッド
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = Offset(0f, yPos),
                end = Offset(width, yPos),
                strokeWidth = 1.dp.toPx()
            )
            // Y軸数値
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.1f", value),
                -10f,
                yPos + 10f,
                textPaint
            )
        }

        // 2. 単位ラベル
        drawContext.canvas.nativeCanvas.drawText("µS", -10f, -25f, labelPaint)

        labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText("Time ->", width, height + 40f, labelPaint)

        // 3. 折れ線
        val path = Path()
        val stepX = width / (data.size - 1).coerceAtLeast(1)

        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = height - ((value - minY) / (maxY - minY) * height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = Color(0xFF007AFF),
            style = Stroke(width = 3.dp.toPx())
        )

        // 4. 軸線
        drawLine(Color.Gray, Offset(0f, 0f), Offset(0f, height), 2.dp.toPx()) // Y軸
        drawLine(Color.Gray, Offset(0f, height), Offset(width, height), 2.dp.toPx()) // X軸
    }
}

// --- 既存のUIコンポーネント (リスト・ビューワー) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdaListScreen(refreshTrigger: Boolean, onFileClick: (EdaFile) -> Unit) {
    val context = LocalContext.current
    var fileList by remember { mutableStateOf(listOf<EdaFile>()) }
    LaunchedEffect(refreshTrigger) { fileList = loadEdaFiles(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("History", fontWeight = FontWeight.Bold, fontSize = 34.sp) },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        if (fileList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No CSV files found", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                items(fileList) { file ->
                    FileCard(file = file, onClick = { onFileClick(file) })
                    Spacer(modifier = Modifier.height(12.dp))
                }
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
fun FileCard(file: EdaFile, onClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(file.dateAdded * 1000))
    val sizeStr = String.format("%.1f KB", file.size / 1024.0)

    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = Color(0xFF007AFF), modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.Black)
                Text("$dateStr • $sizeStr", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvViewerScreen(file: EdaFile, onBack: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    var csvData by remember { mutableStateOf(listOf<List<String>>()) }
    LaunchedEffect(file.uri) { withContext(Dispatchers.IO) { csvData = readCsvContent(context, file.uri) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color(0xFF007AFF)) } },
                actions = { IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share", tint = Color(0xFF007AFF)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF2F2F7))
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding).background(Color.White)) {
            item {
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFE5E5EA)).padding(8.dp)) {
                    csvData.firstOrNull()?.forEach { Text(it, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
            }
            itemsIndexed(csvData.drop(1)) { _, row ->
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        row.forEach { Text(it, modifier = Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
                }
            }
        }
    }
}

// Utils
fun readCsvContent(context: Context, uri: Uri): List<List<String>> {
    val result = mutableListOf<List<String>>()
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.forEachLine { result.add(it.split(",")) }
            }
        }
    } catch (e: Exception) { Log.e("CsvReader", "Error", e) }
    return result
}

fun loadEdaFiles(context: Context): List<EdaFile> {
    val list = mutableListOf<EdaFile>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
    context.contentResolver.query(
        collection,
        arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.SIZE),
        "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("EDA_Received_%"), "${MediaStore.Downloads.DATE_ADDED} DESC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
        while (c.moveToNext()) list.add(EdaFile(c.getLong(idCol), c.getString(nameCol), ContentUris.withAppendedId(collection, c.getLong(idCol)), c.getLong(dateCol), c.getLong(sizeCol)))
    }
    return list
}

fun shareFile(context: Context, uri: Uri) {
    try {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "text/csv"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share CSV"))
    } catch (e: Exception) { Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show() }
}
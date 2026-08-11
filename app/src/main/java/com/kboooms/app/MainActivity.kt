package com.kboooms.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

const val CLOUDINARY_CLOUD_NAME = "wjjl4wjm"
const val CLOUDINARY_UPLOAD_PRESET = "kboooms_preset"

// --- Colores de marca ---
val BrandPurple = Color(0xFF2E1049)
val BrandMagenta = Color(0xFFB0148A)
val BrandOrange = Color(0xFFFF8C1E)
val BrandBg = Color(0xFF15101F)
val BrandSurface = Color(0xFF201A30)

val QuickEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

data class KUser(val uid: String = "", val name: String = "")
data class KPhoto(
    val id: String = "",
    val imageUrl: String = "",
    val caption: String = "",
    val uploaderUid: String = "",
    val uploaderName: String = "",
    val allowedViewers: List<String> = emptyList(),
    val timestamp: Long = 0L,
    val reactions: Map<String, String> = emptyMap()
)

class MainActivity : ComponentActivity() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kboooms_prefs", MODE_PRIVATE)

        setContent {
            val colors = darkColorScheme(
                primary = BrandMagenta,
                secondary = BrandOrange,
                background = BrandBg,
                surface = BrandSurface,
                onPrimary = Color.White,
                onBackground = Color.White,
                onSurface = Color.White
            )
            MaterialTheme(colorScheme = colors) {
                var uid by remember { mutableStateOf(auth.currentUser?.uid) }
                var username by remember { mutableStateOf(prefs.getString("username", null)) }

                LaunchedEffect(Unit) {
                    if (auth.currentUser == null) {
                        auth.signInAnonymously().addOnSuccessListener { uid = it.user?.uid }
                    }
                }

                when {
                    uid == null -> LoadingScreen()
                    username == null -> RegisterScreen { name ->
                        prefs.edit().putString("username", name).apply()
                        db.collection("users").document(uid!!).set(KUser(uid!!, name))
                        username = name
                    }
                    else -> AppScreen(uid!!, username!!, db, this)
                }
            }
        }
    }
}

@Composable
fun GradientHeader(title: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(BrandPurple, BrandMagenta, BrandOrange)))
            .padding(vertical = 20.dp, horizontal = 20.dp)
    ) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(BrandBg), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BrandOrange)
    }
}

@Composable
fun RegisterScreen(onRegistered: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BrandPurple, BrandBg))),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("kboooms", color = BrandOrange, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Elige tu nombre de usuario", color = Color.White)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text("Tu nombre") }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (name.isNotBlank()) onRegistered(name.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandMagenta)
            ) { Text("Continuar") }
        }
    }
}

@Composable
fun AppScreen(myUid: String, myName: String, db: FirebaseFirestore, activity: ComponentActivity) {
    var tab by remember { mutableStateOf(0) }
    var users by remember { mutableStateOf(listOf<KUser>()) }
    var photos by remember { mutableStateOf(listOf<KPhoto>()) }

    DisposableEffect(Unit) {
        val usersReg = db.collection("users").addSnapshotListener { snap, _ ->
            users = snap?.toObjects(KUser::class.java) ?: emptyList()
        }
        val photosReg = db.collection("photos").orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val all = snap?.toObjects(KPhoto::class.java) ?: emptyList()
                photos = all.filter {
                    it.allowedViewers.isEmpty() || it.uploaderUid == myUid || it.allowedViewers.contains(myUid)
                }
            }
        onDispose { usersReg.remove(); photosReg.remove() }
    }

    Scaffold(
        containerColor = BrandBg,
        bottomBar = {
            NavigationBar(containerColor = BrandSurface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("🏠", fontSize = 20.sp) }, label = { Text("Feed") },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = BrandMagenta)
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("👥", fontSize = 20.sp) }, label = { Text("Usuarios") },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = BrandMagenta)
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("📷", fontSize = 20.sp) }, label = { Text("Subir") },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = BrandMagenta)
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> Column {
                    GradientHeader("kboooms")
                    FeedScreen(photos, myUid, db)
                }
                1 -> Column {
                    GradientHeader("Usuarios")
                    UsersTab(users, photos, myUid, db)
                }
                else -> Column {
                    GradientHeader("Nueva publicación")
                    UploadScreen(myUid, myName, users.filter { it.uid != myUid }, db, activity) { tab = 0 }
                }
            }
        }
    }
}

fun formatDate(ts: Long): String {
    if (ts == 0L) return ""
    return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ts))
}

@Composable
fun ReactionRow(photo: KPhoto, myUid: String, db: FirebaseFirestore) {
    val myReaction = photo.reactions[myUid]
    val counts = photo.reactions.values.groupingBy { it }.eachCount()

    Column {
        Row(Modifier.padding(top = 6.dp)) {
            QuickEmojis.forEach { emoji ->
                val isSelected = myReaction == emoji
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) BrandMagenta.copy(alpha = 0.4f) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(emoji, fontSize = 18.sp, modifier = Modifier.clickable {
                        val ref = db.collection("photos").document(photo.id)
                        if (isSelected) {
                            ref.update("reactions.$myUid", FieldValue.delete())
                        } else {
                            ref.update("reactions.$myUid", emoji)
                        }
                    })
                }
            }
        }
        if (counts.isNotEmpty()) {
            Text(
                counts.entries.joinToString("  ") { "${it.key} ${it.value}" },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// pequeño helper de click reusando Modifier.clickable
fun Modifier.clickable(onClick: () -> Unit): Modifier = this.then(
    androidx.compose.ui.Modifier.clickable(indication = null, interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { onClick() }
)

@Composable
fun PhotoCard(photo: KPhoto, myUid: String, db: FirebaseFirestore) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = BrandSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(bottom = 10.dp)) {
            AsyncImage(
                model = photo.imageUrl, contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(photo.uploaderName, color = BrandOrange, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(formatDate(photo.timestamp), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
                if (photo.caption.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(photo.caption, color = Color.White)
                }
                ReactionRow(photo, myUid, db)
            }
        }
    }
}

@Composable
fun FeedScreen(photos: List<KPhoto>, myUid: String, db: FirebaseFirestore) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(photos) { photo -> PhotoCard(photo, myUid, db) }
    }
}

@Composable
fun UsersTab(users: List<KUser>, photos: List<KPhoto>, myUid: String, db: FirebaseFirestore) {
    var selectedUser by remember { mutableStateOf<KUser?>(null) }

    if (selectedUser == null) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(users) { u ->
                val count = photos.count { it.uploaderUid == u.uid }
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = BrandSurface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp).clickable { selectedUser = u },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(50))
                                .background(Brush.linearGradient(listOf(BrandMagenta, BrandOrange))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                u.name.take(1).uppercase(),
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (u.uid == myUid) "${u.name} (tú)" else u.name,
                                color = Color.White, fontWeight = FontWeight.Bold
                            )
                            Text("$count foto(s)", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    } else {
        val u = selectedUser!!
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { selectedUser = null }) {
                    Text("← Volver", color = BrandOrange)
                }
                Spacer(Modifier.width(4.dp))
                Text(u.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            val userPhotos = photos.filter { it.uploaderUid == u.uid }
            if (userPhotos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay fotos para mostrar", color = Color.White.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(userPhotos) { photo -> PhotoCard(photo, myUid, db) }
                }
            }
        }
    }
}

@Composable
fun UploadScreen(
    myUid: String, myName: String, otherUsers: List<KUser>,
    db: FirebaseFirestore, activity: ComponentActivity, onDone: () -> Unit
) {
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    val selectedViewers = remember { mutableStateMapOf<String, Boolean>() }
    var uploading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImage = uri
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { launcher.launch("image/*") },
            colors = ButtonDefaults.buttonColors(containerColor = BrandMagenta)
        ) { Text("Elegir foto") }

        selectedImage?.let {
            Spacer(Modifier.height(12.dp))
            AsyncImage(
                model = it, contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp))
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = caption, onValueChange = { caption = it },
            placeholder = { Text("Escribe una nota o descripción (opcional)") },
            modifier = Modifier.fillMaxWidth()
        )

        if (otherUsers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("¿Quién puede ver esta foto? (nadie marcado = todos)", color = Color.White)
            otherUsers.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = selectedViewers[u.uid] ?: false,
                        onCheckedChange = { selectedViewers[u.uid] = it },
                        colors = CheckboxDefaults.colors(checkedColor = BrandOrange)
                    )
                    Text(u.name, color = Color.White)
                }
            }
        }

        errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = selectedImage != null && !uploading,
            colors = ButtonDefaults.buttonColors(containerColor = BrandOrange),
            onClick = {
                uploading = true
                errorMsg = null
                val uri = selectedImage!!

                Thread {
                    try {
                        val inputStream = activity.contentResolver.openInputStream(uri)
                        val tempFile = File.createTempFile("upload", ".jpg", activity.cacheDir)
                        val out = FileOutputStream(tempFile)
                        inputStream?.copyTo(out)
                        inputStream?.close()
                        out.close()

                        val client = OkHttpClient()
                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "file", tempFile.name,
                                tempFile.asRequestBody("image/*".toMediaType())
                            )
                            .addFormDataPart("upload_preset", CLOUDINARY_UPLOAD_PRESET)
                            .build()

                        val request = Request.Builder()
                            .url("https://api.cloudinary.com/v1_1/$CLOUDINARY_CLOUD_NAME/image/upload")
                            .post(requestBody)
                            .build()

                        client.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string()
                            if (!response.isSuccessful || bodyStr == null) {
                                Handler(Looper.getMainLooper()).post {
                                    uploading = false
                                    errorMsg = "Error al subir la imagen"
                                }
                                return@Thread
                            }
                            val json = JSONObject(bodyStr)
                            val secureUrl = json.getString("secure_url")

                            val photoId = UUID.randomUUID().toString()
                            val viewers = selectedViewers.filterValues { it }.keys.toList()
                            val photo = KPhoto(
                                photoId, secureUrl, caption.trim(), myUid, myName,
                                viewers, System.currentTimeMillis(), emptyMap()
                            )

                            db.collection("photos").document(photoId).set(photo)
                                .addOnSuccessListener {
                                    Handler(Looper.getMainLooper()).post {
                                        uploading = false
                                        selectedImage = null
                                        caption = ""
                                        selectedViewers.clear()
                                        onDone()
                                    }
                                }
                                .addOnFailureListener {
                                    Handler(Looper.getMainLooper()).post {
                               

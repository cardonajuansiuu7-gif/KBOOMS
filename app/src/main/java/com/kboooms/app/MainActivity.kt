package com.kboooms.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import java.util.UUID
import kotlin.math.roundToInt

const val CLOUDINARY_CLOUD_NAME = "wjjl4wjm"
const val CLOUDINARY_UPLOAD_PRESET = "kboooms_preset"

val REACTION_EMOJIS = listOf("❤️", "😂", "😮", "😢", "👍", "🔥")

// --- Colores de marca (inspirados en la portada) ---
val DeepPurple = Color(0xFF190A3C)
val Surface1 = Color(0xFF241040)
val Surface2 = Color(0xFF2E1550)
val Magenta = Color(0xFFC91F8C)
val Orange = Color(0xFFFF8C1E)

val KBoooomsColors = darkColorScheme(
    primary = Magenta,
    secondary = Orange,
    background = DeepPurple,
    surface = Surface1,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Surface2
)

data class KUser(val uid: String = "", val name: String = "")
data class KPhoto(
    val id: String = "",
    val imageUrl: String = "",
    val uploaderUid: String = "",
    val uploaderName: String = "",
    val allowedViewers: List<String> = emptyList(),
    val timestamp: Long = 0L,
    val caption: String = "",
    val reactions: Map<String, String> = emptyMap()
)

class MainActivity : ComponentActivity() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kboooms_prefs", MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = KBoooomsColors) {
                var uid by remember { mutableStateOf(auth.currentUser?.uid) }
                var username by remember { mutableStateOf(prefs.getString("username", null)) }

                LaunchedEffect(Unit) {
                    if (auth.currentUser == null) {
                        auth.signInAnonymously().addOnSuccessListener { uid = it.user?.uid }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
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
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Magenta)
    }
}

@Composable
fun RegisterScreen(onRegistered: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeepPurple, Magenta, Orange))),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("kboooms", fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text("Comparte fotos con tu gente", color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Tu nombre de usuario") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.15f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.15f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (name.isNotBlank()) onRegistered(name.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Magenta),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Continuar", fontWeight = FontWeight.Bold) }
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("kboooms", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("📷", fontSize = 20.sp) }, label = { Text("Feed") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("👥", fontSize = 20.sp) }, label = { Text("Usuarios") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("➕", fontSize = 20.sp) }, label = { Text("Subir") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> FeedScreen(photos, myUid, db)
                1 -> UsersScreen(users.filter { it.uid != myUid }, photos, myUid, db)
                2 -> UploadScreen(myUid, myName, users.filter { it.uid != myUid }, db, activity) { tab = 0 }
            }
        }
    }
}

@Composable
fun FeedScreen(photos: List<KPhoto>, myUid: String, db: FirebaseFirestore) {
    if (photos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Aún no hay fotos. ¡Sube la primera!", color = Color.White.copy(alpha = 0.6f))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(photos, key = { it.id }) { photo ->
            PhotoCard(photo, myUid, db)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
fun UsersScreen(otherUsers: List<KUser>, allPhotos: List<KPhoto>, myUid: String, db: FirebaseFirestore) {
    var selected by remember { mutableStateOf<KUser?>(null) }

    if (selected == null) {
        if (otherUsers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Todavía no hay más usuarios registrados", color = Color.White.copy(alpha = 0.6f))
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(otherUsers, key = { it.uid }) { u ->
                val count = allPhotos.count { it.uploaderUid == u.uid }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface1)
                        .clickable { selected = u }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Magenta, Orange))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(u.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(u.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("$count foto${if (count == 1) "" else "s"}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    } else {
        val u = selected!!
        val userPhotos = allPhotos.filter { it.uploaderUid == u.uid }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { selected = null }) { Text("← Volver", color = Color.White) }
                Spacer(Modifier.width(6.dp))
                Text(u.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            if (userPhotos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("${u.name} no ha subido fotos que puedas ver", color = Color.White.copy(alpha = 0.6f))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(userPhotos, key = { it.id }) { photo ->
                        AsyncImage(
                            model = photo.imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoCard(photo: KPhoto, myUid: String, db: FirebaseFirestore) {
    // --- Animación de entrada tipo "3D": inclina y desvanece al aparecer ---
    val rotation = remember { Animatable(35f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(photo.id) {
        rotation.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 90f))
        alpha.animateTo(1f, tween(350))
    }

    var showHeart by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val myReaction = photo.reactions[myUid]

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationX = rotation.value
                cameraDistance = 14 * density
                this.alpha = alpha.value
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = photo.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .combinedClickable(
                            onClick = {},
                            onDoubleClick = {
                                showHeart = true
                                toggleReaction(db, photo.id, myUid, "❤️", myReaction, forceAdd = true)
                            }
                        )
                )
                // Corazón animado tipo doble-tap
                val heartScale = remember { Animatable(0f) }
                LaunchedEffect(showHeart) {
                    if (showHeart) {
                        heartScale.snapTo(0f)
                        heartScale.animateTo(1.3f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        heartScale.animateTo(1f, tween(150))
                        kotlinx_delay(500)
                        showHeart = false
                    }
                }
                if (showHeart) {
                    Text(
                        "❤️",
                        fontSize = 90.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                scaleX = heartScale.value
                                scaleY = heartScale.value
                            }
                    )
                }
            }

            Column(Modifier.padding(14.dp)) {
                Text(photo.uploaderName, color = Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (photo.caption.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(photo.caption, color = Color.White, fontSize = 14.sp)
                }

                Spacer(Modifier.height(10.dp))

                // Resumen de reacciones existentes
                val counts = photo.reactions.values.groupingBy { it }.eachCount()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    counts.forEach { (emoji, count) ->
                        AssistChip(
                            onClick = { toggleReaction(db, photo.id, myUid, emoji, myReaction) },
                            label = { Text("$emoji $count", fontSize = 12.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (myReaction == emoji) Magenta.copy(alpha = 0.5f) else Surface2
                            ),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    IconButtonEmoji(onClick = { pickerOpen = !pickerOpen })
                }

                AnimatedVisibility(visible = pickerOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Row(Modifier.padding(top = 10.dp)) {
                        REACTION_EMOJIS.forEach { emoji ->
                            Text(
                                emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .clickable {
                                        toggleReaction(db, photo.id, myUid, emoji, myReaction)
                                        pickerOpen = false
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IconButtonEmoji(onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Surface2)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text("😀+", fontSize = 12.sp, color = Color.White)
    }
}

fun toggleReaction(
    db: FirebaseFirestore, photoId: String, myUid: String,
    emoji: String, current: String?, forceAdd: Boolean = false
) {
    val ref = db.collection("photos").document(photoId)
    if (!forceAdd && current == emoji) {
        ref.update("reactions.$myUid", FieldValue.delete())
    } else {
        ref.update("reactions.$myUid", emoji)
    }
}

// pequeño helper de espera sin traer coroutines extra
suspend fun kotlinx_delay(ms: Long) = kotlinx.coroutines.delay(ms)

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
            colors = ButtonDefaults.buttonColors(containerColor = Magenta)
        ) { Text("Elegir foto") }

        selectedImage?.let {
            Spacer(Modifier.height(12.dp))
            AsyncImage(
                model = it, contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("Escribe una nota pa

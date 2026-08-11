package com.kboooms.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

const val CLOUDINARY_CLOUD_NAME = "wjjl4wjm"
const val CLOUDINARY_UPLOAD_PRESET = "kboooms_preset"

data class KUser(val uid: String = "", val name: String = "")
data class KPhoto(
    val id: String = "",
    val imageUrl: String = "",
    val uploaderUid: String = "",
    val uploaderName: String = "",
    val allowedViewers: List<String> = emptyList(),
    val timestamp: Long = 0L
)

class MainActivity : ComponentActivity() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kboooms_prefs", MODE_PRIVATE)

        setContent {
            MaterialTheme {
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
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun RegisterScreen(onRegistered: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Bienvenido a kboooms", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text("Elige tu nombre de usuario")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { if (name.isNotBlank()) onRegistered(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Continuar")
            }
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

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = {}, label = { Text("Feed") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("Subir") })
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            if (tab == 0) FeedScreen(photos)
            else UploadScreen(myUid, myName, users.filter { it.uid != myUid }, db, activity) { tab = 0 }
        }
    }
}

@Composable
fun FeedScreen(photos: List<KPhoto>) {
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(photos) { photo ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column {
                    AsyncImage(model = photo.imageUrl, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(280.dp))
                    Text("Subido por: ${photo.uploaderName}", modifier = Modifier.padding(8.dp))
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
    val selectedViewers = remember { mutableStateMapOf<String, Boolean>() }
    var uploading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImage = uri
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { launcher.launch("image/*") }) { Text("Elegir foto") }

        selectedImage?.let {
            Spacer(Modifier.height(12.dp))
            AsyncImage(model = it, contentDescription = null, modifier = Modifier.height(200.dp))
        }

        if (otherUsers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("¿Quién puede ver esta foto? (nadie marcado = todos)")
            otherUsers.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selectedViewers[u.uid] ?: false,
                        onCheckedChange = { selectedViewers[u.uid] = it })
                    Text(u.name)
                }
            }
        }

        errorMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(enabled = selectedImage != null && !uploading, onClick = {
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
                        val photo = KPhoto(photoId, secureUrl, myUid, myName, viewers, System.currentTimeMillis())

                        db.collection("photos").document(photoId).set(photo)
                            .addOnSuccessListener {
                                Handler(Looper.getMainLooper()).post {
                                    uploading = false
                                    selectedImage = null
                                    selectedViewers.clear()
                                    onDone()
                                }
                            }
                            .addOnFailureListener {
                                Handler(Looper.getMainLooper()).post {
                                    uploading = false
                                    errorMsg = "Error al guardar en Firestore"
                                }
                            }
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        uploading = false
                        errorMsg = "Error: ${e.message}"
                    }
                }
            }.start()
        }) { Text(if (uploading) "Subiendo..." else "Publicar") }
    }
}

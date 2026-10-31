package app.pocketvideotoolkit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Opens Android's document/file manager or the photo/video Gallery picker. */
@Composable
internal fun rememberMediaSourcePicker(
    mimeTypes: Array<String>,
    allowMultiple: Boolean = false,
    onSelected: (List<Uri>) -> Unit
): () -> Unit {
    var choosingSource by remember { mutableStateOf(false) }
    val filesSingle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSelected(listOf(uri))
    }
    val filesMultiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onSelected(uris)
    }
    val samsungGallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selected = buildList {
                result.data?.data?.let(::add)
                result.data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                }
            }.distinct()
            if (selected.isNotEmpty()) onSelected(selected)
        }
    }
    val gallerySingle = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onSelected(listOf(uri))
    }
    val galleryMultiple = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onSelected(uris)
    }
    val pickImages = mimeTypes.any { it.startsWith("image/") }
    val galleryType = if (pickImages) ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.VideoOnly
    val openAndroidGallery = {
        val request = PickVisualMediaRequest(galleryType)
        if (allowMultiple) galleryMultiple.launch(request) else gallerySingle.launch(request)
    }

    if (choosingSource) AlertDialog(
        onDismissRequest = { choosingSource = false },
        title = { Text("Choose a source") },
        text = { Text("Browse device folders in the file manager, or choose directly from Gallery.") },
        confirmButton = {
            TextButton(onClick = {
                choosingSource = false
                if (allowMultiple) filesMultiple.launch(mimeTypes) else filesSingle.launch(mimeTypes)
            }) { Text("File manager") }
        },
        dismissButton = {
            TextButton(onClick = {
                choosingSource = false
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = mimeTypes.firstOrNull() ?: "*/*"
                    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setPackage(SAMSUNG_GALLERY_PACKAGE)
                    if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    samsungGallery.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    openAndroidGallery()
                }
            }) { Text("Gallery") }
        }
    )
    return remember { { choosingSource = true } }
}

private const val SAMSUNG_GALLERY_PACKAGE = "com.sec.android.gallery3d"

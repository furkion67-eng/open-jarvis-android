package com.openjarvis.deviceaccess.storage

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-selected shared-storage browser; no silent whole-device scan. */
@Composable
fun StorageAccessPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grantRegistry = remember(context) { StorageUriGrantRegistry(context) }
    var treeUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var parentDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<SafStorageGateway.Entry>>(emptyList()) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedForDeletion by remember { mutableStateOf<SafStorageGateway.Entry?>(null) }
    var typedDeleteName by rememberSaveable { mutableStateOf("") }
    var selectedForRename by remember { mutableStateOf<SafStorageGateway.Entry?>(null) }
    var renameValue by rememberSaveable { mutableStateOf("") }
    var pendingTransferSource by remember { mutableStateOf<StorageTransferSource?>(null) }
    var pendingTransfer by remember { mutableStateOf<PendingStorageTransfer?>(null) }
    var status by rememberSaveable { mutableStateOf("Henüz klasör erişimi verilmedi.") }
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            try {
                grantRegistry.persistPickerResult(uri, data?.flags ?: 0)
                treeUriText = uri.toString()
                parentDocumentId = DocumentsContract.getTreeDocumentId(uri)
                status = "Seçilen klasör izni kaydedildi; uygulama yeniden açıldığında tekrar kontrol edilecek."
            } catch (error: SecurityException) {
                status = "Android klasör iznini kalıcı yapmadı: " + (error.message ?: "izin reddedildi")
            } catch (error: Exception) {
                status = "Klasör izni kaydedilemedi: " + (error.message ?: "bilinmeyen hata")
            }
        } else if (result.resultCode != Activity.RESULT_OK) {
            status = "Klasör seçimi iptal edildi."
        }
    }

    val transferPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pendingTransferSource
        val data = result.data
        val destinationTree = data?.data
        if (result.resultCode == Activity.RESULT_OK && destinationTree != null && source != null) {
            try {
                grantRegistry.persistPickerResult(destinationTree, data.flags, makeLast = false)
                val hasWriteGrant = context.contentResolver.persistedUriPermissions.any {
                    it.uri == destinationTree && it.isWritePermission
                }
                if (!hasWriteGrant) {
                    status = "Android hedef klasör için yazma izni vermedi. Kopyalama veya taşıma yapılamaz."
                } else {
                    val rootId = DocumentsContract.getTreeDocumentId(destinationTree)
                    val targetParent = DocumentsContract.buildDocumentUriUsingTree(destinationTree, rootId)
                    scope.launch {
                        try {
                            val targetName = withContext(Dispatchers.IO) {
                                SafStorageGateway(context).displayName(targetParent)
                            }
                            pendingTransfer = PendingStorageTransfer(
                                preview = StorageTransferPreview(
                                    operation = source.operation,
                                    sourceUri = source.entry.uri.toString(),
                                    sourceName = source.entry.name,
                                    targetParentUri = targetParent.toString(),
                                    targetName = targetName
                                ),
                                sourceParentUri = source.sourceParentUri
                            )
                            status = "Hedef klasör seçildi. İşlem, aşağıdaki açık onaydan sonra yapılır."
                        } catch (error: Exception) {
                            status = "Hedef klasör doğrulanamadı: " + (error.message ?: "erişim hatası")
                        }
                    }
                }
            } catch (error: Exception) {
                status = "Hedef klasör izni alınamadı: " + (error.message ?: "izin hatası")
            }
        } else if (result.resultCode != Activity.RESULT_OK) {
            status = "Hedef klasör seçimi iptal edildi."
        }
        pendingTransferSource = null
    }

    LaunchedEffect(Unit) {
        val restored = withContext(Dispatchers.IO) { grantRegistry.lastGrantedTreeUri() }
        if (treeUriText == null && restored != null) {
            treeUriText = restored.toString()
            parentDocumentId = DocumentsContract.getTreeDocumentId(restored)
            status = "Önceki klasör izni bulundu ve yeniden doğrulandı."
        }
    }

    val treeUri = treeUriText?.let(Uri::parse)
    val beginTransfer: (SafStorageGateway.Entry, StorageTransferOperation) -> Unit = { entry, operation ->
        val activeTree = treeUri
        val activeParentId = parentDocumentId
        if (activeTree == null || activeParentId == null) {
            status = "Önce erişim izni olan bir klasör seç."
        } else {
            val sourceParent = DocumentsContract.buildDocumentUriUsingTree(activeTree, activeParentId)
            pendingTransferSource = StorageTransferSource(entry, operation, sourceParent)
            val request = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
                if (Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, activeTree)
            }
            transferPicker.launch(request)
        }
    }

    LaunchedEffect(treeUriText, parentDocumentId, refreshKey) {
        val selectedTree = treeUri
        val selectedParent = parentDocumentId
        if (selectedTree != null && selectedParent != null) {
            loading = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    SafStorageGateway(context).listChildren(selectedTree, selectedParent)
                }
                entries = loaded
                status = loaded.size.toString() + " öğe listelendi."
            } catch (error: Exception) {
                entries = emptyList()
                status = "Klasöre erişilemedi. İzin kaldırılmış olabilir: " + (error.message ?: "bilinmeyen hata")
            } finally {
                loading = false
            }
        }
    }

    val visibleEntries = entries.filter {
        searchQuery.isBlank() || it.name.contains(searchQuery.trim(), ignoreCase = true)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Telefon dosyaları", style = MaterialTheme.typography.titleMedium)
            Text(
                "JARVIS yalnızca Android dosya seçicisinde senin seçtiğin klasörlere erişir.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val request = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        )
                        if (treeUri != null && Build.VERSION.SDK_INT >= 26) {
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
                        }
                    }
                    picker.launch(request)
                }) {
                    Text(if (treeUri == null) "Klasör seç" else "Başka klasör seç")
                }
                if (treeUri != null) {
                    Button(onClick = {
                        parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                        selectedForDeletion = null
                        searchQuery = ""
                    }) { Text("Ana klasör") }
                    Button(onClick = { refreshKey++ }) { Text("Yenile") }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (loading) Text("Dosyalar yükleniyor…")

            if (treeUri != null && parentDocumentId != null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Bu klasörde dosya ara") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Yeni klasör adı") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = folderName.isNotBlank() && !loading,
                    onClick = {
                        val name = folderName.trim()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId!!)
                                    SafStorageGateway(context).createDirectory(parent, name)
                                }
                                folderName = ""
                                status = "Klasör oluşturuldu."
                                refreshKey++
                            } catch (error: Exception) {
                                status = "Klasör oluşturulamadı: " + (error.message ?: "sağlayıcı hatası")
                            }
                        }
                    }
                ) { Text("Klasör oluştur") }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(visibleEntries, key = { it.uri.toString() }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (entry.isDirectory) {
                                    parentDocumentId = DocumentsContract.getDocumentId(entry.uri)
                                    selectedForDeletion = null
                                    searchQuery = ""
                                } else {
                                    val view = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(entry.uri, entry.mimeType ?: "*/*")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(view)
                                    } catch (_: Exception) {
                                        status = "Bu dosya türünü açabilecek uygulama bulunamadı."
                                    }
                                }
                            }
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    (if (entry.isDirectory) "📁 " else "📄 ") + entry.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (!entry.isDirectory) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        androidx.compose.material3.TextButton(onClick = {
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = entry.mimeType ?: "*/*"
                                                putExtra(Intent.EXTRA_STREAM, entry.uri)
                                                clipData = ClipData.newUri(context.contentResolver, entry.name, entry.uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            try {
                                                context.startActivity(Intent.createChooser(share, "Dosyayı paylaş"))
                                            } catch (_: Exception) {
                                                status = "Dosya paylaşım uygulaması bulunamadı."
                                            }
                                        }) { Text("Paylaş") }
                                        TextButton(onClick = {
                                            selectedForDeletion = entry
                                            typedDeleteName = ""
                                        }) { Text("Silme onayı") }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        selectedForRename = entry
                                        renameValue = entry.name
                                    }) { Text("Yeniden adlandır") }
                                    TextButton(onClick = { beginTransfer(entry, StorageTransferOperation.COPY) }) {
                                        Text("Kopyala…")
                                    }
                                    TextButton(onClick = { beginTransfer(entry, StorageTransferOperation.MOVE) }) {
                                        Text("Taşı…")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            selectedForRename?.let { entry ->
                val requestedName = renameValue.trim()
                AlertDialog(
                    onDismissRequest = { selectedForRename = null },
                    title = { Text("Dosyayı yeniden adlandır") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Mevcut ad: " + entry.name)
                            OutlinedTextField(
                                value = renameValue,
                                onValueChange = { renameValue = it },
                                label = { Text("Yeni dosya adı") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("Yeni ad, onay verdiğinde uygulanır.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = requestedName.isNotBlank() && requestedName != entry.name,
                            onClick = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val gateway = SafStorageGateway(context)
                                            val currentName = gateway.displayName(entry.uri)
                                            val preview = StorageRenamePreview(
                                                uri = entry.uri.toString(),
                                                originalName = entry.name,
                                                newName = requestedName
                                            )
                                            require(
                                                StorageMutationPolicy.renameMatches(
                                                    preview,
                                                    entry.uri.toString(),
                                                    currentName,
                                                    requestedName
                                                )
                                            ) { "Dosya adı önizlemeden sonra değişti; işlemi yeniden başlat." }
                                            gateway.rename(entry.uri, requestedName)
                                        }
                                        selectedForRename = null
                                        renameValue = ""
                                        status = "Dosya yeniden adlandırıldı."
                                        refreshKey++
                                    } catch (error: Exception) {
                                        status = "Dosya yeniden adlandırılamadı: " + (error.message ?: "işlem başarısız")
                                    }
                                }
                            }
                        ) { Text("Onayla ve yeniden adlandır") }
                    },
                    dismissButton = {
                        TextButton(onClick = { selectedForRename = null }) { Text("Vazgeç") }
                    }
                )
            }

            pendingTransfer?.let { transfer ->
                val preview = transfer.preview
                val actionLabel = if (preview.operation == StorageTransferOperation.COPY) "kopyala" else "taşı"
                AlertDialog(
                    onDismissRequest = { pendingTransfer = null },
                    title = { Text(if (preview.operation == StorageTransferOperation.COPY) "Kopyalama onayı" else "Taşıma onayı") },
                    text = {
                        Text(
                            "Kaynak: ${preview.sourceName}\nHedef klasör: ${preview.targetName}\n\n" +
                                "JARVIS bu işlemi yalnızca sen onayladıktan sonra yapacak."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val gateway = SafStorageGateway(context)
                                            val sourceUri = Uri.parse(preview.sourceUri)
                                            val targetParent = Uri.parse(preview.targetParentUri)
                                            val currentSourceName = gateway.displayName(sourceUri)
                                            val currentTargetName = gateway.displayName(targetParent)
                                            require(
                                                StorageMutationPolicy.transferMatches(
                                                    preview,
                                                    sourceUri.toString(),
                                                    currentSourceName,
                                                    targetParent.toString(),
                                                    currentTargetName
                                                )
                                            ) { "Kaynak veya hedef klasör değişti; işlemi yeniden onayla." }
                                            when (preview.operation) {
                                                StorageTransferOperation.COPY -> gateway.copy(sourceUri, targetParent)
                                                StorageTransferOperation.MOVE -> gateway.move(
                                                    sourceUri,
                                                    transfer.sourceParentUri,
                                                    targetParent
                                                )
                                            }
                                        }
                                        pendingTransfer = null
                                        status = if (preview.operation == StorageTransferOperation.COPY) {
                                            "Dosya veya klasör kopyalandı."
                                        } else {
                                            "Dosya veya klasör taşındı."
                                        }
                                        refreshKey++
                                    } catch (error: Exception) {
                                        status = "Dosya işlemi tamamlanamadı: " + (error.message ?: actionLabel)
                                    }
                                }
                            }
                        ) { Text(if (preview.operation == StorageTransferOperation.COPY) "Onayla ve kopyala" else "Onayla ve taşı") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingTransfer = null }) { Text("Vazgeç") }
                    }
                )
            }

            selectedForDeletion?.let { entry ->
                Text("Silinecek dosya: " + entry.name, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = typedDeleteName,
                    onValueChange = { typedDeleteName = it },
                    label = { Text("Onaylamak için dosya adını aynen yaz") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = typedDeleteName == entry.name,
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val gateway = SafStorageGateway(context)
                                    val confirmation = gateway.confirmDeletion(entry.uri, typedDeleteName)
                                    gateway.delete(entry.uri, confirmation)
                                }
                                selectedForDeletion = null
                                typedDeleteName = ""
                                status = "Dosya silindi."
                                refreshKey++
                            } catch (error: Exception) {
                                status = "Dosya silinemedi: " + (error.message ?: "işlem başarısız")
                            }
                        }
                    }
                ) { Text("Onayla ve sil") }
            }
        }
    }
}


private data class StorageTransferSource(
    val entry: SafStorageGateway.Entry,
    val operation: StorageTransferOperation,
    val sourceParentUri: Uri
)

private data class PendingStorageTransfer(
    val preview: StorageTransferPreview,
    val sourceParentUri: Uri
)

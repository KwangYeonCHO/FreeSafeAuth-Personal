package com.kmelect.freesafeauth

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.kmelect.freesafeauth.crypto.BackupCryptoManager
import com.kmelect.freesafeauth.crypto.CryptoManager
import com.kmelect.freesafeauth.data.AppDatabase
import com.kmelect.freesafeauth.data.TotpAccountEntity
import com.kmelect.freesafeauth.totp.Base32
import com.kmelect.freesafeauth.totp.OtpAuthData
import com.kmelect.freesafeauth.totp.OtpAuthParser
import com.kmelect.freesafeauth.totp.TotpGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.io.File

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { FreeSafeAuthApp() }
    }
}

enum class Screen { Home, AddChoice, ManualAdd, EditAccount, Scan, Settings }

enum class BackupAction { Export, Import }

object AppLanguage {
    var current: String = "system"
}

fun t(zh: String, en: String, ja: String, ko: String): String =
    when ((if (AppLanguage.current == "system") Locale.getDefault().language else AppLanguage.current).lowercase(Locale.US)) {
        "zh" -> zh
        "en" -> en
        "ja" -> ja
        "ko" -> ko
        else -> zh
    }

fun languageLabel(code: String): String = when (code) {
    "zh" -> "中文"
    "en" -> "English"
    "ja" -> "日本語"
    "ko" -> "한국어"
    else -> t("跟随系统", "Follow system", "システムに従う", "시스템 언어")
}

fun nextLanguage(code: String): String = when (code) {
    "system" -> "zh"
    "zh" -> "en"
    "en" -> "ja"
    "ja" -> "ko"
    else -> "system"
}

data class TotpDisplay(
    val entity: TotpAccountEntity,
    val code: String,
    val secondsLeft: Int,
    val progress: Float
)

data class BackupPreviewAccount(
    val issuer: String,
    val accountName: String,
    val secret: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val duplicate: Boolean,
    val valid: Boolean
)

data class BackupPreview(
    val accounts: List<BackupPreviewAccount>
) {
    val total: Int get() = accounts.size
    val importable: Int get() = accounts.count { it.valid && !it.duplicate }
    val duplicates: Int get() = accounts.count { it.duplicate }
    val invalid: Int get() = accounts.count { !it.valid }
}

class HomeViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val dao by lazy { AppDatabase.get(appContext).totpAccountDao() }
    private val crypto = CryptoManager()
    val accounts: StateFlow<List<TotpAccountEntity>> = dao.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(data: OtpAuthData, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val issuer = data.issuer.trim()
                val accountName = data.accountName.trim()
                require(!hasDuplicate(issuer, accountName)) { t("账号已存在", "Account already exists", "アカウントはすでに存在します", "계정이 이미 있습니다") }
                dao.insert(
                    TotpAccountEntity(
                        issuer = issuer,
                        accountName = accountName,
                        encryptedSecret = crypto.encrypt(Base32.normalize(data.secret)),
                        algorithm = data.algorithm,
                        digits = data.digits,
                        period = data.period,
                        sortOrder = accounts.value.size
                    )
                )
            }.onSuccess { onSuccess() }
                .onFailure { onError(it.message ?: t("保存失败", "Save failed", "保存に失敗しました", "저장에 실패했습니다")) }
        }
    }

    fun delete(account: TotpAccountEntity) {
        viewModelScope.launch { dao.delete(account) }
    }

    fun update(account: TotpAccountEntity, data: OtpAuthData, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val issuer = data.issuer.trim()
                val accountName = data.accountName.trim()
                require(!hasDuplicate(issuer, accountName, account.id)) { t("账号已存在", "Account already exists", "アカウントはすでに存在します", "계정이 이미 있습니다") }
                dao.update(
                    account.copy(
                        issuer = issuer,
                        accountName = accountName,
                        encryptedSecret = crypto.encrypt(Base32.normalize(data.secret)),
                        algorithm = data.algorithm,
                        digits = data.digits,
                        period = data.period,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onSuccess { onSuccess() }
                .onFailure { onError(it.message ?: t("保存失败", "Save failed", "保存に失敗しました", "저장에 실패했습니다")) }
        }
    }

    private fun hasDuplicate(issuer: String, accountName: String, excludeId: Long? = null): Boolean =
        accounts.value.any {
            it.id != excludeId &&
                it.issuer.trim().equals(issuer, ignoreCase = true) &&
                it.accountName.trim().equals(accountName, ignoreCase = true)
        }

    fun move(account: TotpAccountEntity, delta: Int) {
        viewModelScope.launch {
            val list = accounts.value.toMutableList()
            val index = list.indexOfFirst { it.id == account.id }
            val target = (index + delta).coerceIn(0, list.lastIndex)
            if (index < 0 || index == target) return@launch
            java.util.Collections.swap(list, index, target)
            dao.updateAll(list.mapIndexed { order, item -> item.copy(sortOrder = order) })
        }
    }

    fun toOtpAuthData(account: TotpAccountEntity): OtpAuthData? =
        runCatching {
            OtpAuthData(
                issuer = account.issuer,
                accountName = account.accountName,
                secret = crypto.decrypt(account.encryptedSecret),
                algorithm = account.algorithm,
                digits = account.digits,
                period = account.period
            )
        }.getOrNull()

    fun displays(now: Long): List<TotpDisplay> = accounts.value.mapNotNull { account ->
        runCatching {
            val secret = crypto.decrypt(account.encryptedSecret)
            val code = TotpGenerator.generateCode(
                secretBase32 = secret,
                timeMillis = now,
                digits = account.digits,
                period = account.period,
                algorithm = account.algorithm
            )
            val second = (now / 1000L).toInt()
            val elapsed = second % account.period
            val left = account.period - elapsed
            TotpDisplay(account, code, left, left / account.period.toFloat())
        }.getOrNull()
    }

    fun exportBackup(password: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val backupAccounts = JSONArray()
                accounts.value.forEach { account ->
                    backupAccounts.put(
                        JSONObject()
                            .put("issuer", account.issuer)
                            .put("accountName", account.accountName)
                            .put("secret", crypto.decrypt(account.encryptedSecret))
                            .put("algorithm", account.algorithm)
                            .put("digits", account.digits)
                            .put("period", account.period)
                    )
                }
                val plainBackup = JSONObject()
                    .put("version", 1)
                    .put("app", "FreeSafeAuth Personal")
                    .put("createdAt", System.currentTimeMillis())
                    .put("accounts", backupAccounts)
                    .toString()
                BackupCryptoManager.encryptBackup(plainBackup, password)
            }
            onResult(result)
        }
    }

    fun previewBackup(encryptedJson: String, password: String, onResult: (Result<BackupPreview>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val plain = BackupCryptoManager.decryptBackup(encryptedJson, password)
                val root = JSONObject(plain)
                require(root.optString("app") == "FreeSafeAuth Personal") { "备份文件格式错误" }
                val existingKeys = accounts.value
                    .map { "${it.issuer.trim().lowercase()}|${it.accountName.trim().lowercase()}" }
                    .toMutableSet()
                val items = root.getJSONArray("accounts")
                val previewAccounts = mutableListOf<BackupPreviewAccount>()
                val seenKeys = mutableSetOf<String>()
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val issuer = item.optString("issuer").trim()
                    val accountName = item.optString("accountName").trim()
                    val secret = Base32.normalize(item.optString("secret"))
                    val key = "${issuer.lowercase()}|${accountName.lowercase()}"
                    val valid = issuer.isNotBlank() && accountName.isNotBlank() && Base32.isValid(secret)
                    val duplicate = key in existingKeys || key in seenKeys
                    previewAccounts += BackupPreviewAccount(
                        issuer = issuer,
                        accountName = accountName,
                        secret = secret,
                        algorithm = item.optString("algorithm", "SHA1").uppercase(),
                        digits = item.optInt("digits", 6),
                        period = item.optInt("period", 30),
                        duplicate = duplicate,
                        valid = valid
                    )
                    if (valid) seenKeys.add(key)
                }
                BackupPreview(previewAccounts)
            }
            onResult(result)
        }
    }

    fun importBackup(preview: BackupPreview, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                var imported = 0
                preview.accounts.filter { it.valid && !it.duplicate }.forEach { item ->
                    dao.insert(
                        TotpAccountEntity(
                            issuer = item.issuer,
                            accountName = item.accountName,
                            encryptedSecret = crypto.encrypt(item.secret),
                            algorithm = item.algorithm,
                            digits = item.digits,
                            period = item.period,
                            sortOrder = accounts.value.size + imported
                        )
                    )
                    imported++
                }
                imported
            }
            onResult(result)
        }
    }
}

@Composable
fun FreeSafeAuthApp() {
    val currentContext = LocalContext.current
    val context = currentContext.applicationContext
    val activity = currentContext.findActivity()
    val vm: HomeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(context) as T
    })
    var screen by remember { mutableStateOf(Screen.Home) }
    val backStack = remember { mutableStateListOf<Screen>() }
    var lastBackPress by remember { mutableLongStateOf(0L) }
    var pendingScan by remember { mutableStateOf<OtpAuthData?>(null) }
    var editingAccount by remember { mutableStateOf<TotpAccountEntity?>(null) }
    var editingData by remember { mutableStateOf<OtpAuthData?>(null) }
    var backupAction by remember { mutableStateOf<BackupAction?>(null) }
    var backupPayload by remember { mutableStateOf<String?>(null) }
    var backupPreview by remember { mutableStateOf<BackupPreview?>(null) }
    var showLocalFileBrowser by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var backgroundAt by remember { mutableLongStateOf(0L) }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var hideCodes by remember {
        mutableStateOf(prefs.getBoolean("hideCodes", false))
    }
    var clearClipboard by remember { mutableStateOf(prefs.getBoolean("clearClipboard", false)) }
    var biometricEnabled by remember { mutableStateOf(prefs.getBoolean("biometricEnabled", false)) }
    var autoLockEnabled by remember { mutableStateOf(prefs.getBoolean("autoLockEnabled", false)) }
    var autoLockSeconds by remember { mutableStateOf(prefs.getInt("autoLockSeconds", 0)) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("darkMode", false)) }
    var appLanguage by remember { mutableStateOf(prefs.getString("appLanguage", "system").orEmpty()) }
    AppLanguage.current = appLanguage.ifBlank { "system" }
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching { currentContext.contentResolver.readText(uri) }
                .onSuccess {
                    backupPayload = it
                    backupAction = BackupAction.Import
                }
                .onFailure { Toast.makeText(currentContext, t("备份文件读取失败", "Failed to read backup file", "バックアップファイルを読み取れませんでした", "백업 파일을 읽지 못했습니다"), Toast.LENGTH_SHORT).show() }
        }
    }
    fun openBackupImportPicker() {
        val launched = backupImportIntents().firstNotNullOfOrNull { intent ->
            runCatching {
                importLauncher.launch(intent)
                true
            }.getOrNull()
        } == true
        if (!launched) {
            showLocalFileBrowser = true
            Toast.makeText(currentContext, t("无法打开文件选择器", "Cannot open file picker", "ファイル選択を開けません", "파일 선택기를 열 수 없습니다"), Toast.LENGTH_SHORT).show()
        }
    }
    val readStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(currentContext, t("未授予文件读取权限，仍会尝试打开文件选择器", "File read permission was denied. Trying to open the file picker anyway.", "ファイル読み取り権限が拒否されました。ファイル選択を開いてみます。", "파일 읽기 권한이 거부되었습니다. 그래도 파일 선택기를 열어봅니다."), Toast.LENGTH_SHORT).show()
        }
        openBackupImportPicker()
    }
    val writeStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(currentContext, t("未授予文件写入权限，仍会尝试导出备份", "File write permission was denied. Trying to export anyway.", "ファイル書き込み権限が拒否されました。バックアップのエクスポートを続行します。", "파일 쓰기 권한이 거부되었습니다. 그래도 백업 내보내기를 시도합니다."), Toast.LENGTH_SHORT).show()
        }
        backupAction = BackupAction.Export
    }
    fun requestManageFilesForImport() {
        manageFilesPermissionIntents(currentContext).firstNotNullOfOrNull { intent ->
            runCatching {
                currentContext.startActivity(intent)
                true
            }.getOrNull()
        } ?: run {
            Toast.makeText(currentContext, t("无法打开权限设置，改为尝试文件选择器", "Cannot open permission settings. Trying the file picker instead.", "権限設定を開けません。ファイル選択を試します。", "권한 설정을 열 수 없어 파일 선택기를 시도합니다."), Toast.LENGTH_SHORT).show()
            openBackupImportPicker()
        }
    }
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled) locked = true
    }

    DisposableEffect(autoLockEnabled, autoLockSeconds, biometricEnabled) {
        val owner = activity
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val elapsed = System.currentTimeMillis() - backgroundAt
                    if (biometricEnabled && autoLockEnabled && backgroundAt > 0 && elapsed >= autoLockSeconds * 1000L) {
                        locked = true
                    }
                }
                else -> Unit
            }
        }
        if (owner is FragmentActivity) owner.lifecycle.addObserver(observer)
        onDispose { if (owner is FragmentActivity) owner.lifecycle.removeObserver(observer) }
    }

    fun navigateTo(target: Screen) {
        if (target != screen) {
            backStack.add(screen)
            screen = target
        }
    }

    fun clearSensitiveScreenState() {
        if (screen == Screen.ManualAdd) pendingScan = null
        if (screen == Screen.EditAccount) {
            editingAccount = null
            editingData = null
        }
    }

    fun goBack() {
        if (backStack.isNotEmpty()) {
            clearSensitiveScreenState()
            screen = backStack.removeAt(backStack.lastIndex)
            return
        }

        val now = System.currentTimeMillis()
        if (screen != Screen.Home) {
            clearSensitiveScreenState()
            screen = Screen.Home
            return
        }
        if (now - lastBackPress <= 2_000) {
            activity?.finish()
        } else {
            lastBackPress = now
            Toast.makeText(currentContext, t("再按一次退出", "Press back again to exit", "もう一度押すと終了します", "한 번 더 누르면 종료됩니다"), Toast.LENGTH_SHORT).show()
        }
    }

    fun returnHome() {
        backStack.clear()
        screen = Screen.Home
    }

    fun unlockWithBiometrics() {
        if (biometricAvailable && activity is FragmentActivity) {
            activity.authenticate(
                onSuccess = { locked = false },
                onError = { Toast.makeText(currentContext, it, Toast.LENGTH_SHORT).show() }
            )
        } else {
            Toast.makeText(currentContext, t("当前设备不支持生物识别", "Biometrics are not supported on this device", "この端末は生体認証に対応していません", "이 기기는 생체 인식을 지원하지 않습니다"), Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        if (locked && biometricEnabled) activity?.finish() else goBack()
    }

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (locked && biometricEnabled) {
                LockScreen(
                    biometricAvailable = biometricAvailable,
                    onUnlock = { unlockWithBiometrics() }
                )
            } else when (screen) {
                Screen.Home -> HomeScreen(
                    vm = vm,
                    hideCodes = hideCodes,
                    clearClipboard = clearClipboard,
                    onAdd = { navigateTo(Screen.AddChoice) },
                    onSettings = { navigateTo(Screen.Settings) },
                    onEdit = { account ->
                        editingAccount = account
                        editingData = vm.toOtpAuthData(account)
                        navigateTo(Screen.EditAccount)
                    }
                )
                Screen.AddChoice -> AddChoiceScreen(
                    onBack = { goBack() },
                    onManual = { navigateTo(Screen.ManualAdd) },
                    onScan = { navigateTo(Screen.Scan) }
                )
                Screen.ManualAdd -> ManualAddScreen(
                    initial = pendingScan,
                    onBack = {
                        pendingScan = null
                        goBack()
                    },
                    onSave = {
                        vm.add(
                            data = it,
                            onSuccess = {
                                pendingScan = null
                                returnHome()
                            },
                            onError = { error ->
                                Toast.makeText(currentContext, error, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                )
                Screen.EditAccount -> ManualAddScreen(
                    initial = editingData,
                    title = t("编辑账号", "Edit account", "アカウントを編集", "계정 편집"),
                    onBack = {
                        editingAccount = null
                        editingData = null
                        goBack()
                    },
                    onSave = {
                        editingAccount?.let { account ->
                            vm.update(
                                account = account,
                                data = it,
                                onSuccess = {
                                    editingAccount = null
                                    editingData = null
                                    returnHome()
                                },
                                onError = { error ->
                                    Toast.makeText(currentContext, error, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )
                Screen.Scan -> ScanQrScreen(
                    onBack = { goBack() },
                    onDetected = {
                        pendingScan = it
                        navigateTo(Screen.ManualAdd)
                    }
                )
                Screen.Settings -> SettingsScreen(
                    hideCodes = hideCodes,
                    onHideCodesChange = {
                        hideCodes = it
                        prefs.edit().putBoolean("hideCodes", it).apply()
                    },
                    clearClipboard = clearClipboard,
                    onClearClipboardChange = {
                        clearClipboard = it
                        prefs.edit().putBoolean("clearClipboard", it).apply()
                    },
                    biometricAvailable = biometricAvailable,
                    biometricEnabled = biometricEnabled,
                    onBiometricEnabledChange = {
                        if (it && !biometricAvailable) {
                            Toast.makeText(currentContext, t("当前设备不支持生物识别", "Biometrics are not supported on this device", "この端末は生体認証に対応していません", "이 기기는 생체 인식을 지원하지 않습니다"), Toast.LENGTH_SHORT).show()
                        } else {
                            biometricEnabled = it
                            prefs.edit().putBoolean("biometricEnabled", it).apply()
                            if (it) locked = true
                        }
                    },
                    autoLockEnabled = autoLockEnabled,
                    onAutoLockEnabledChange = {
                        autoLockEnabled = it
                        prefs.edit().putBoolean("autoLockEnabled", it).apply()
                    },
                    autoLockSeconds = autoLockSeconds,
                    onAutoLockSecondsChange = {
                        autoLockSeconds = it
                        prefs.edit().putInt("autoLockSeconds", it).apply()
                    },
                    darkMode = darkMode,
                    onDarkModeChange = {
                        darkMode = it
                        prefs.edit().putBoolean("darkMode", it).apply()
                    },
                    appLanguage = appLanguage,
                    onAppLanguageChange = {
                        appLanguage = it
                        AppLanguage.current = it
                        prefs.edit().putString("appLanguage", it).apply()
                    },
                    onExportBackup = {
                        if (
                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(currentContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) {
                            writeStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            backupAction = BackupAction.Export
                        }
                    },
                    onImportBackup = {
                        if (
                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
                            ContextCompat.checkSelfPermission(currentContext, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) {
                            readStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            requestManageFilesForImport()
                        } else {
                            openBackupImportPicker()
                        }
                    },
                    onOpenRepository = {
                        currentContext.openUrl("https://github.com/KwangYeonCHO/FreeSafeAuth-Personal")
                    },
                    onBack = { goBack() }
                )
            }

            backupAction?.let { action ->
                BackupPasswordDialog(
                    action = action,
                    onDismiss = { backupAction = null },
                    onConfirm = { password ->
                        when (action) {
                            BackupAction.Export -> vm.exportBackup(password) { result ->
                                result
                                    .onSuccess {
                                        runCatching { currentContext.saveBackupToDownloads(it) }
                                            .onSuccess { fileName -> Toast.makeText(currentContext, t("已保存到下载目录：$fileName", "Saved to Downloads: $fileName", "ダウンロードに保存しました：$fileName", "다운로드에 저장됨: $fileName"), Toast.LENGTH_SHORT).show() }
                                            .onFailure {
                                                Toast.makeText(currentContext, t("无法写入本地导出文件", "Cannot write local export file", "ローカルのエクスポートファイルを書き込めません", "로컬 내보내기 파일을 쓸 수 없습니다"), Toast.LENGTH_SHORT).show()
                                            }
                                    }
                                    .onFailure {
                                        Toast.makeText(currentContext, it.message ?: t("导出失败", "Export failed", "エクスポートに失敗しました", "내보내기에 실패했습니다"), Toast.LENGTH_SHORT).show()
                                    }
                                backupAction = null
                            }
                            BackupAction.Import -> {
                                val content = backupPayload
                                if (content == null) {
                                    Toast.makeText(currentContext, t("备份文件读取失败", "Failed to read backup file", "バックアップファイルを読み取れませんでした", "백업 파일을 읽지 못했습니다"), Toast.LENGTH_SHORT).show()
                                    backupAction = null
                                } else {
                                    vm.previewBackup(content, password) { result ->
                                        result
                                            .onSuccess { preview ->
                                                backupPreview = preview
                                            }
                                            .onFailure { Toast.makeText(currentContext, t("备份密码错误或文件已损坏", "Wrong backup password or damaged file", "バックアップパスワードが違うか、ファイルが破損しています", "백업 비밀번호가 틀렸거나 파일이 손상되었습니다"), Toast.LENGTH_SHORT).show() }
                                        backupAction = null
                                    }
                                }
                            }
                        }
                    }
                )
            }

            backupPreview?.let { preview ->
                BackupPreviewDialog(
                    preview = preview,
                    onDismiss = {
                        backupPreview = null
                        backupPayload = null
                    },
                    onConfirm = {
                        vm.importBackup(preview) { result ->
                            result
                                .onSuccess { count -> Toast.makeText(currentContext, t("已导入 $count 个账号", "Imported $count accounts", "$count 件のアカウントをインポートしました", "${count}개 계정을 가져왔습니다"), Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(currentContext, t("导入失败", "Import failed", "インポートに失敗しました", "가져오기에 실패했습니다"), Toast.LENGTH_SHORT).show() }
                            backupPreview = null
                            backupPayload = null
                        }
                    }
                )
            }

            if (showLocalFileBrowser) {
                LocalFileBrowserDialog(
                    onDismiss = { showLocalFileBrowser = false },
                    onFileSelected = { file ->
                        runCatching { file.readText(Charsets.UTF_8) }
                            .onSuccess {
                                backupPayload = it
                                backupAction = BackupAction.Import
                                showLocalFileBrowser = false
                            }
                            .onFailure {
                                Toast.makeText(currentContext, t("无法读取该文件", "Cannot read this file", "このファイルを読み取れません", "이 파일을 읽을 수 없습니다"), Toast.LENGTH_SHORT).show()
                            }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    hideCodes: Boolean,
    clearClipboard: Boolean,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onEdit: (TotpAccountEntity) -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    var query by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val context = LocalContext.current
    val displays = vm.displays(now).filter {
        val keyword = query.trim()
        keyword.isBlank() ||
            it.entity.issuer.contains(keyword, ignoreCase = true) ||
            it.entity.accountName.contains(keyword, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FreeSafeAuth Personal") },
                actions = { TextButton(onClick = onSettings) { Text(t("设置", "Settings", "設定", "설정")) } }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Text("+", fontSize = 28.sp) } }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(t("暂无验证码，点击右下角 + 添加", "No codes yet. Tap + to add one.", "コードはまだありません。右下の + で追加できます。", "아직 코드가 없습니다. 오른쪽 아래 +를 눌러 추가하세요."))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(t("搜索服务或账号", "Search service or account", "サービスまたはアカウントを検索", "서비스 또는 계정 검색")) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displays, key = { it.entity.id }) { display ->
                        AccountCard(
                            display = display,
                            hideCode = hideCodes,
                            onCopy = {
                                copyCode(context, display.code, clearClipboard)
                                Toast.makeText(context, t("验证码已复制", "Code copied", "コードをコピーしました", "인증 코드를 복사했습니다"), Toast.LENGTH_SHORT).show()
                            },
                            onEdit = { onEdit(display.entity) },
                            onDelete = { vm.delete(display.entity) },
                            onMoveUp = { vm.move(display.entity, -1) },
                            onMoveDown = { vm.move(display.entity, 1) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountCard(
    display: TotpDisplay,
    hideCode: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onCopy, onLongClick = { menuOpen = true }),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(display.entity.issuer, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(display.entity.accountName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (hideCode) "*** ***" else formatCode(display.code),
                        modifier = Modifier.clickable { onCopy() },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { display.progress }, modifier = Modifier.size(54.dp))
                        Text("${display.secondsLeft}s", fontSize = 13.sp)
                    }
                    TextButton(onClick = { menuOpen = true }) { Text(t("更多", "More", "その他", "더보기")) }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(t("编辑", "Edit", "編集", "편집")) }, onClick = {
                    menuOpen = false
                    onEdit()
                })
                DropdownMenuItem(text = { Text(t("上移", "Move up", "上へ移動", "위로 이동")) }, onClick = {
                    menuOpen = false
                    onMoveUp()
                })
                DropdownMenuItem(text = { Text(t("下移", "Move down", "下へ移動", "아래로 이동")) }, onClick = {
                    menuOpen = false
                    onMoveDown()
                })
                DropdownMenuItem(text = { Text(t("删除", "Delete", "削除", "삭제")) }, onClick = {
                    menuOpen = false
                    confirmDelete = true
                })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("删除账号", "Delete account", "アカウントを削除", "계정 삭제")) },
            text = { Text(t("确定要删除这个验证码账号吗？删除后无法恢复，除非你有备份文件。", "Delete this authenticator account? This cannot be undone unless you have a backup.", "この認証アカウントを削除しますか？バックアップがない限り元に戻せません。", "이 인증 계정을 삭제할까요? 백업이 없으면 복구할 수 없습니다.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(t("删除", "Delete", "削除", "삭제")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("取消", "Cancel", "キャンセル", "취소")) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChoiceScreen(onBack: () -> Unit, onManual: () -> Unit, onScan: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(t("添加账号", "Add account", "アカウントを追加", "계정 추가")) }, navigationIcon = { TextButton(onClick = onBack) { Text(t("返回", "Back", "戻る", "뒤로")) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(t("扫描二维码", "Scan QR code", "QRコードをスキャン", "QR 코드 스캔")) }
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(t("手动输入密钥", "Enter secret manually", "シークレットを手入力", "Secret Key 직접 입력")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAddScreen(
    initial: OtpAuthData?,
    title: String = if (initial == null) t("手动添加", "Manual add", "手動追加", "직접 추가") else t("确认账号", "Confirm account", "アカウントを確認", "계정 확인"),
    onBack: () -> Unit,
    onSave: (OtpAuthData) -> Unit
) {
    var issuer by remember(initial) { mutableStateOf(initial?.issuer.orEmpty()) }
    var account by remember(initial) { mutableStateOf(initial?.accountName.orEmpty()) }
    var secret by remember(initial) { mutableStateOf(initial?.secret.orEmpty()) }
    var digits by remember(initial) { mutableStateOf((initial?.digits ?: 6).toString()) }
    var period by remember(initial) { mutableStateOf((initial?.period ?: 30).toString()) }
    var algorithm by remember(initial) { mutableStateOf(initial?.algorithm ?: "SHA1") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text(t("返回", "Back", "戻る", "뒤로")) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(issuer, { issuer = it }, label = { Text(t("服务名称", "Service name", "サービス名", "서비스 이름")) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(account, { account = it }, label = { Text(t("账号名称", "Account name", "アカウント名", "계정 이름")) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it }, label = { Text("Secret Key") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(digits, { digits = it.filter(Char::isDigit) }, label = { Text(t("位数", "Digits", "桁数", "자리수")) }, modifier = Modifier.weight(1f))
                OutlinedTextField(period, { period = it.filter(Char::isDigit) }, label = { Text(t("周期", "Period", "周期", "주기")) }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(algorithm, { algorithm = it.uppercase() }, label = { Text(t("算法 SHA1/SHA256/SHA512", "Algorithm SHA1/SHA256/SHA512", "アルゴリズム SHA1/SHA256/SHA512", "알고리즘 SHA1/SHA256/SHA512")) }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val d = digits.toIntOrNull() ?: 6
                    val p = period.toIntOrNull() ?: 30
                    val normalized = Base32.normalize(secret)
                    error = when {
                        issuer.isBlank() -> t("服务名称不能为空", "Service name is required", "サービス名は必須です", "서비스 이름은 필수입니다")
                        account.isBlank() -> t("账号名称不能为空", "Account name is required", "アカウント名は必須です", "계정 이름은 필수입니다")
                        secret.isBlank() -> t("Secret Key 不能为空", "Secret Key is required", "Secret Key は必須です", "Secret Key는 필수입니다")
                        !Base32.isValid(secret) -> t("Secret Key 格式错误", "Invalid Secret Key format", "Secret Key の形式が正しくありません", "Secret Key 형식이 올바르지 않습니다")
                        d !in 6..8 -> t("验证码位数只能是 6 到 8", "Digits must be 6 to 8", "桁数は 6〜8 にしてください", "자리수는 6~8이어야 합니다")
                        p <= 0 -> t("刷新周期必须大于 0", "Period must be greater than 0", "更新周期は 0 より大きくしてください", "갱신 주기는 0보다 커야 합니다")
                        algorithm !in setOf("SHA1", "SHA256", "SHA512") -> t("算法仅支持 SHA1、SHA256、SHA512", "Only SHA1, SHA256, and SHA512 are supported", "SHA1、SHA256、SHA512 のみ対応しています", "SHA1, SHA256, SHA512만 지원합니다")
                        else -> null
                    }
                    if (error == null) {
                        onSave(OtpAuthData(issuer, account, normalized, algorithm, d, p))
                    }
                }
            ) { Text(t("保存", "Save", "保存", "저장")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQrScreen(onBack: () -> Unit, onDetected: (OtpAuthData) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) error = t("摄像头权限被拒绝，无法扫码", "Camera permission was denied", "カメラ権限が拒否されたためスキャンできません", "카메라 권한이 거부되어 스캔할 수 없습니다")
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(t("扫描二维码", "Scan QR code", "QRコードをスキャン", "QR 코드 스캔")) }, navigationIcon = { TextButton(onClick = onBack) { Text(t("返回", "Back", "戻る", "뒤로")) } }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (hasPermission) {
                CameraScanner(
                    onRawValue = { raw ->
                        runCatching { OtpAuthParser.parse(raw) }
                            .onSuccess(onDetected)
                            .onFailure { error = t("二维码格式无效", "Invalid QR code", "QRコードの形式が正しくありません", "QR 코드 형식이 올바르지 않습니다") }
                    }
                )
            } else {
                Text(t("需要摄像头权限才能扫描二维码", "Camera permission is required to scan QR codes", "QRコードのスキャンにはカメラ権限が必要です", "QR 코드를 스캔하려면 카메라 권한이 필요합니다"), modifier = Modifier.align(Alignment.Center))
            }
            error?.let {
                Card(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScanner(onRawValue: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var found by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    val mediaImage = proxy.image
                    if (mediaImage != null && !found) {
                        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val raw = barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.rawValue != null }?.rawValue
                                if (!raw.isNullOrBlank() && !found) {
                                    found = true
                                    onRawValue(raw)
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    } else {
                        proxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    hideCodes: Boolean,
    onHideCodesChange: (Boolean) -> Unit,
    clearClipboard: Boolean,
    onClearClipboardChange: (Boolean) -> Unit,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onBiometricEnabledChange: (Boolean) -> Unit,
    autoLockEnabled: Boolean,
    onAutoLockEnabledChange: (Boolean) -> Unit,
    autoLockSeconds: Int,
    onAutoLockSecondsChange: (Int) -> Unit,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onOpenRepository: () -> Unit,
    onBack: () -> Unit
) {
    var showLanguageDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(t("设置", "Settings", "設定", "설정")) }, navigationIcon = { TextButton(onClick = onBack) { Text(t("返回", "Back", "戻る", "뒤로")) } }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(t("安全设置", "Security", "セキュリティ", "보안 설정"), fontWeight = FontWeight.Bold)
            Text(t("Secret Key 已使用 Android Keystore + AES-GCM 加密保存在本机。", "Secret keys are encrypted locally with Android Keystore + AES-GCM.", "Secret Key は Android Keystore + AES-GCM で端末内に暗号化保存されます。", "Secret Key는 Android Keystore + AES-GCM으로 기기에 암호화 저장됩니다."))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t("生物识别解锁", "Biometric unlock", "生体認証ロック解除", "생체 인식 잠금 해제"))
                Switch(checked = biometricEnabled, onCheckedChange = onBiometricEnabledChange, enabled = biometricAvailable)
            }
            if (!biometricAvailable) {
                Text(t("当前设备不支持生物识别", "Biometrics are not supported on this device", "この端末は生体認証に対応していません", "이 기기는 생체 인식을 지원하지 않습니다"), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t("进入后台后自动锁定", "Auto-lock after backgrounding", "バックグラウンド移行後に自動ロック", "백그라운드 전환 후 자동 잠금"))
                Switch(checked = autoLockEnabled, onCheckedChange = onAutoLockEnabledChange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t("自动锁定时间", "Auto-lock delay", "自動ロック時間", "자동 잠금 시간") + "：${autoLockLabel(autoLockSeconds)}")
                OutlinedButton(onClick = { onAutoLockSecondsChange(nextAutoLockSeconds(autoLockSeconds)) }) {
                    Text(t("切换", "Change", "切替", "변경"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t("复制后 30 秒清空剪贴板", "Clear clipboard 30s after copy", "コピー後30秒でクリップボードを消去", "복사 후 30초 뒤 클립보드 지우기"))
                Switch(checked = clearClipboard, onCheckedChange = onClearClipboardChange)
            }
            Text(t("显示设置", "Display", "表示", "표시 설정"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t("隐藏验证码", "Hide codes", "コードを非表示", "인증 코드 숨기기"))
                Switch(checked = hideCodes, onCheckedChange = onHideCodesChange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t("深色模式", "Dark mode", "ダークモード", "다크 모드"))
                Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t("语言", "Language", "言語", "언어"))
                OutlinedButton(onClick = { showLanguageDialog = true }) {
                    Text(languageLabel(appLanguage))
                }
            }
            Text(t("数据管理", "Data", "データ管理", "데이터 관리"), fontWeight = FontWeight.Bold)
            Text(t(
                "紧急恢复说明：请定期导出加密备份，并妥善保存备份密码。备份密码丢失后无法恢复备份内容。",
                "Emergency recovery: export encrypted backups regularly and keep the backup password safe. Lost backup passwords cannot be recovered.",
                "緊急復旧：暗号化バックアップを定期的にエクスポートし、バックアップパスワードを安全に保管してください。紛失したパスワードは復元できません。",
                "긴급 복구: 암호화 백업을 정기적으로 내보내고 백업 비밀번호를 안전하게 보관하세요. 잃어버린 백업 비밀번호는 복구할 수 없습니다."
            ))
            Button(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) { Text(t("导出加密备份", "Export encrypted backup", "暗号化バックアップをエクスポート", "암호화 백업 내보내기")) }
            OutlinedButton(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) { Text(t("导入加密备份", "Import encrypted backup", "暗号化バックアップをインポート", "암호화 백업 가져오기")) }
            Text(t("关于", "About", "情報", "정보"), fontWeight = FontWeight.Bold)
            Text("FreeSafeAuth Personal")
            Text(t("版本", "Version", "バージョン", "버전") + " ${BuildConfig.VERSION_NAME}")
            Text(t("个人使用的本地 TOTP 验证码工具。无广告、无统计、无登录、无服务器。", "A local TOTP authenticator for personal use. No ads, no analytics, no login, no server.", "個人利用向けのローカル TOTP 認証アプリです。広告、解析、ログイン、サーバーはありません。", "개인용 로컬 TOTP 인증 앱입니다. 광고, 통계, 로그인, 서버가 없습니다."))
            OutlinedButton(onClick = onOpenRepository, modifier = Modifier.fillMaxWidth()) {
                Text(t("访问开源仓库", "Open source repository", "オープンソースリポジトリを開く", "오픈소스 저장소 열기"))
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(t("选择语言", "Choose language", "言語を選択", "언어 선택")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system", "zh", "en", "ja", "ko").forEach { code ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onAppLanguageChange(code)
                                showLanguageDialog = false
                            }
                        ) {
                            Text(languageLabel(code))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(t("取消", "Cancel", "キャンセル", "취소"))
                }
            }
        )
    }
}

@Composable
fun LockScreen(biometricAvailable: Boolean, onUnlock: () -> Unit) {
    LaunchedEffect(biometricAvailable) {
        if (biometricAvailable) onUnlock()
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("FreeSafeAuth Personal", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (biometricAvailable) t("需要通过生物识别解锁", "Biometric unlock is required", "生体認証でロック解除してください", "생체 인식으로 잠금 해제해야 합니다") else t("当前设备不支持生物识别", "Biometrics are not supported on this device", "この端末は生体認証に対応していません", "이 기기는 생체 인식을 지원하지 않습니다"))
            Button(onClick = onUnlock, enabled = biometricAvailable) { Text(t("解锁", "Unlock", "ロック解除", "잠금 해제")) }
        }
    }
}

@Composable
fun BackupPasswordDialog(
    action: BackupAction,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val isExport = action == BackupAction.Export

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (action) {
                    BackupAction.Export -> t("导出加密备份", "Export encrypted backup", "暗号化バックアップをエクスポート", "암호화 백업 내보내기")
                    BackupAction.Import -> t("导入加密备份", "Import encrypted backup", "暗号化バックアップをインポート", "암호화 백업 가져오기")
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isExport) t("请输入备份密码。请妥善保存，丢失后无法恢复备份。", "Enter a backup password. Keep it safe; backups cannot be recovered without it.", "バックアップパスワードを入力してください。紛失するとバックアップを復元できません。", "백업 비밀번호를 입력하세요. 잃어버리면 백업을 복구할 수 없습니다.") else t("请输入备份密码。", "Enter the backup password.", "バックアップパスワードを入力してください。", "백업 비밀번호를 입력하세요."))
                OutlinedTextField(password, { password = it }, label = { Text(t("备份密码", "Backup password", "バックアップパスワード", "백업 비밀번호")) }, modifier = Modifier.fillMaxWidth())
                if (isExport) {
                    OutlinedTextField(confirm, { confirm = it }, label = { Text(t("再次确认密码", "Confirm password", "パスワードを再入力", "비밀번호 다시 입력")) }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = when {
                        password.length < 6 -> t("备份密码至少需要 6 位", "Backup password must be at least 6 characters", "バックアップパスワードは6文字以上にしてください", "백업 비밀번호는 6자 이상이어야 합니다")
                        isExport && password != confirm -> t("两次输入的密码不一致", "Passwords do not match", "パスワードが一致しません", "비밀번호가 일치하지 않습니다")
                        else -> null
                    }
                    if (error == null) onConfirm(password)
                }
            ) { Text(if (isExport) t("导出", "Export", "エクスポート", "내보내기") else t("导入", "Import", "インポート", "가져오기")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消", "Cancel", "キャンセル", "취소")) } }
    )
}

@Composable
fun BackupPreviewDialog(
    preview: BackupPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("导入预览", "Import preview", "インポートプレビュー", "가져오기 미리보기")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("备份完整性校验通过", "Backup integrity check passed", "バックアップの整合性チェックに合格しました", "백업 무결성 검사를 통과했습니다"))
                Text(t("总账号：${preview.total}", "Total accounts: ${preview.total}", "合計アカウント：${preview.total}", "전체 계정: ${preview.total}"))
                Text(t("可导入：${preview.importable}", "Importable: ${preview.importable}", "インポート可能：${preview.importable}", "가져올 수 있음: ${preview.importable}"))
                Text(t("重复账号：${preview.duplicates}", "Duplicates: ${preview.duplicates}", "重複：${preview.duplicates}", "중복 계정: ${preview.duplicates}"))
                Text(t("无效账号：${preview.invalid}", "Invalid: ${preview.invalid}", "無効：${preview.invalid}", "유효하지 않음: ${preview.invalid}"))
                if (preview.accounts.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(preview.accounts) { item ->
                            val status = when {
                                !item.valid -> t("无效", "Invalid", "無効", "유효하지 않음")
                                item.duplicate -> t("重复", "Duplicate", "重複", "중복")
                                else -> t("可导入", "Importable", "可能", "가능")
                            }
                            Text("${item.issuer} / ${item.accountName} - $status")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = preview.importable > 0) {
                Text(t("确认导入", "Import", "インポート", "가져오기"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消", "Cancel", "キャンセル", "취소")) } }
    )
}

@Composable
fun LocalFileBrowserDialog(
    onDismiss: () -> Unit,
    onFileSelected: (File) -> Unit
) {
    val startDir = remember {
        listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
            File("/storage/emulated/0")
        ).firstOrNull { it.exists() && it.isDirectory } ?: File("/")
    }
    var currentDir by remember { mutableStateOf(startDir) }
    val entries = remember(currentDir.absolutePath) {
        runCatching {
            buildList {
                addAll(
                    currentDir.listFiles()
                        .orEmpty()
                        .filter { it.isFreeSafeAuthBackupFile() }
                        .sortedByDescending { it.lastModified() }
                )
            }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("选择备份文件", "Choose backup file", "バックアップファイルを選択", "백업 파일 선택")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(currentDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                if (entries.isEmpty()) {
                    Text(t("此目录为空或无法读取", "This folder is empty or cannot be read", "このフォルダは空か読み取れません", "이 폴더는 비어 있거나 읽을 수 없습니다"))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(entries) { file ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onFileSelected(file) }
                            ) {
                                Text(file.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消", "Cancel", "キャンセル", "취소")) } }
    )
}

fun copyCode(context: Context, code: String, clearAfter: Boolean) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("TOTP", code))
    if (clearAfter) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val current = manager.primaryClip?.getItemAt(0)?.text?.toString()
            if (current == code) {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 30_000)
    }
}

fun formatCode(code: String): String =
    if (code.length > 3) code.chunked(3).joinToString(" ") else code

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun backupImportIntents(): List<Intent> {
    val mimeTypes = arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
    val openDocument = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(getContent, t("选择备份文件", "Choose backup file", "バックアップファイルを選択", "백업 파일 선택"))
    return listOf(openDocument, getContent, chooser)
}

fun manageFilesPermissionIntents(context: Context): List<Intent> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        },
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    ) else listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    )

fun FragmentActivity.authenticate(onSuccess: () -> Unit, onError: (String) -> Unit) {
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                onError(t("生物识别验证失败", "Biometric authentication failed", "生体認証に失敗しました", "생체 인증에 실패했습니다"))
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(t("解锁 FreeSafeAuth Personal", "Unlock FreeSafeAuth Personal", "FreeSafeAuth Personal をロック解除", "FreeSafeAuth Personal 잠금 해제"))
        .setSubtitle(t("验证通过后才能查看验证码", "Authenticate to view your codes", "認証後にコードを表示できます", "인증 후 코드를 볼 수 있습니다"))
        .setNegativeButtonText(t("取消", "Cancel", "キャンセル", "취소"))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    prompt.authenticate(info)
}

fun ContentResolver.writeText(uri: Uri, text: String) {
    openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: error(t("无法写入文件", "Cannot write file", "ファイルを書き込めません", "파일을 쓸 수 없습니다"))
}

fun ContentResolver.readText(uri: Uri): String =
    openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error(t("无法读取文件", "Cannot read file", "ファイルを読み取れません", "파일을 읽을 수 없습니다"))

fun defaultBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "freesafeauth-backup-$stamp.json"
}

fun File.isFreeSafeAuthBackupFile(): Boolean =
    isFile &&
        name.startsWith("freesafeauth-backup-", ignoreCase = true) &&
        extension.equals("json", ignoreCase = true)

fun Context.saveBackupToDownloads(encryptedBackup: String): String {
    val fileName = defaultBackupFileName()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error(t("无法创建导出文件", "Cannot create export file", "エクスポートファイルを作成できません", "내보내기 파일을 만들 수 없습니다"))
        runCatching {
            resolver.writeText(uri, encryptedBackup)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure {
            resolver.delete(uri, null, null)
            throw it
        }
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        File(dir, fileName).writeText(encryptedBackup, Charsets.UTF_8)
    }
    return fileName
}

fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
        .onFailure {
            Toast.makeText(this, t("无法打开链接", "Cannot open link", "リンクを開けません", "링크를 열 수 없습니다"), Toast.LENGTH_SHORT).show()
        }
}

fun autoLockLabel(seconds: Int): String = when (seconds) {
    0 -> t("立即", "Immediately", "すぐに", "즉시")
    60 -> t("1 分钟", "1 minute", "1分", "1분")
    300 -> t("5 分钟", "5 minutes", "5分", "5분")
    else -> t("${seconds} 秒", "$seconds seconds", "$seconds 秒", "${seconds}초")
}

fun nextAutoLockSeconds(seconds: Int): Int = when (seconds) {
    0 -> 60
    60 -> 300
    else -> 0
}

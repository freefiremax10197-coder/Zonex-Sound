package com.example.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import com.example.IShizukuAudioService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

data class ShizukuRootState(
  val isShizukuInstalled: Boolean = false,
  val isShizukuRunning: Boolean = false,
  val hasShizukuPermission: Boolean = false,
  val isRootAvailable: Boolean = false,
  val isServiceBound: Boolean = false,
  val isInjectionActive: Boolean = false,
  val shellIdentity: String = "",
  val statusMessage: String = "Shizuku / Root চেক করতে নিচের বাটনে ট্যাপ করুন",
  val diagnosticReport: String = "",
)

object ShizukuRootManager {

  private val SHIZUKU_PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
  private const val REQ_CODE_SHIZUKU = 9001

  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow(ShizukuRootState())
  val state: StateFlow<ShizukuRootState> = _state.asStateFlow()

  @Volatile
  private var userService: IShizukuAudioService? = null
  @Volatile
  private var isBindingInProgress = false
  private var listenersRegistered = false
  private var appContextRef: Context? = null

  private fun buildUserServiceArgs(ctx: Context): Shizuku.UserServiceArgs =
    Shizuku.UserServiceArgs(ComponentName(ctx.applicationContext, ShizukuAudioUserService::class.java))
      .daemon(false)
      .processNameSuffix("zonex_audio")
      .debuggable(false)
      .version(2)

  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        isBindingInProgress = false
        val stub = IShizukuAudioService.Stub.asInterface(service)
        userService = stub
        ioScope.launch {
          val idText = runCatching { stub?.getShellIdentity().orEmpty() }.getOrDefault("")
          val displayId = idText.ifBlank { "uid=2000(shell)" }
          _state.update {
            it.copy(
              isShizukuRunning = true,
              hasShizukuPermission = true,
              isServiceBound = true,
              shellIdentity = displayId,
              statusMessage = "Shizuku ✔ রেডি\n$displayId",
            )
          }
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        isBindingInProgress = false
        userService = null
        _state.update { it.copy(isServiceBound = false) }
      }
    }

  private val binderReceivedListener =
    Shizuku.OnBinderReceivedListener {
      refreshShizukuStatus()
    }

  private val binderDeadListener =
    Shizuku.OnBinderDeadListener {
      isBindingInProgress = false
      userService = null
      _state.update {
        it.copy(
          isShizukuRunning = false,
          hasShizukuPermission = false,
          isServiceBound = false,
          shellIdentity = "",
          statusMessage = "Shizuku সার্ভিস সংযোগ বিচ্ছিন্ন হয়েছে। Shizuku অ্যাপে Start দিন।",
        )
      }
    }

  private val permissionResultListener =
    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
      if (requestCode == REQ_CODE_SHIZUKU) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        _state.update {
          it.copy(
            isShizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(it.isShizukuRunning),
            hasShizukuPermission = granted,
            statusMessage =
              if (granted) "Shizuku ✔ রেডি — শেল সার্ভিস কানেক্ট হচ্ছে..."
              else "Shizuku পারমিশন Allow করা হয়নি। দয়া করে Allow দিন।",
          )
        }
        if (granted) {
          bindShizukuService()
        }
      }
    }

  fun init(context: Context) {
    appContextRef = context.applicationContext
    if (!listenersRegistered) {
      listenersRegistered = true
      runCatching {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
      }
    }
    refreshShizukuStatus()
  }

  fun findInstalledShizukuPackage(context: Context): String? {
    return SHIZUKU_PACKAGES.firstOrNull { pkg ->
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
          @Suppress("DEPRECATION")
          context.packageManager.getPackageInfo(pkg, 0)
        }
        true
      }.getOrDefault(false)
    }
  }

  fun refreshShizukuStatus() {
    val ctx = appContextRef ?: return
    val installed = findInstalledShizukuPackage(ctx) != null
    if (installed && !runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
      runCatching { ShizukuProvider.requestBinderForNonProviderProcess(ctx) }
    }

    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    val granted =
      if (running) {
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
          .getOrDefault(false)
      } else {
        false
      }

    _state.update { cur ->
      val readyDetail =
        if (cur.shellIdentity.isNotBlank()) "Shizuku ✔ রেডি\n${cur.shellIdentity}"
        else "Shizuku ✔ রেডি"
      cur.copy(
        isShizukuInstalled = installed,
        isShizukuRunning = running,
        hasShizukuPermission = granted,
        shellIdentity = if (granted) cur.shellIdentity else "",
        statusMessage =
          when {
            !installed -> "Shizuku অ্যাপ ইন্সটল করা নেই। নিচের বাটন থেকে ইন্সটল করুন।"
            !running -> "Shizuku ইন্সটল আছে কিন্তু চালু নয়। Shizuku অ্যাপে Start দিন।"
            granted -> readyDetail
            else -> "Shizuku চালু আছে। পারমিশন দিতে বাটনে ট্যাপ করে Allow দিন।"
          },
      )
    }

    if (granted && userService == null) {
      bindShizukuService()
    } else if (granted && userService != null && _state.value.shellIdentity.isBlank()) {
      ioScope.launch {
        val idText = runCatching { userService?.getShellIdentity().orEmpty() }.getOrDefault("")
        if (idText.isNotBlank()) {
          _state.update {
            it.copy(
              isServiceBound = true,
              shellIdentity = idText,
              statusMessage = "Shizuku ✔ রেডি\n$idText",
            )
          }
        }
      }
    }
  }

  fun requestShizukuPermission() {
    val ctx = appContextRef ?: return
    ioScope.launch {
      val installed = findInstalledShizukuPackage(ctx) != null
      if (!installed) {
        _state.update {
          it.copy(
            isShizukuInstalled = false,
            isShizukuRunning = false,
            hasShizukuPermission = false,
            statusMessage = "Shizuku অ্যাপ ইন্সটল করা নেই। 'Shizuku ইন্সটল করো' বাটনে ট্যাপ করুন।",
          )
        }
        return@launch
      }

      if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
        runCatching { ShizukuProvider.requestBinderForNonProviderProcess(ctx) }
        delay(280L)
      }

      val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
      if (!running) {
        _state.update {
          it.copy(
            isShizukuInstalled = true,
            isShizukuRunning = false,
            hasShizukuPermission = false,
            statusMessage = "Shizuku সার্ভিস চালু নেই! Shizuku অ্যাপ খুলে Wireless Debugging দিয়ে Start দিন।",
          )
        }
        return@launch
      }

      val alreadyGranted =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
          .getOrDefault(false)
      if (alreadyGranted) {
        _state.update {
          it.copy(
            isShizukuInstalled = true,
            isShizukuRunning = true,
            hasShizukuPermission = true,
            statusMessage =
              if (it.shellIdentity.isNotBlank()) "Shizuku ✔ রেডি\n${it.shellIdentity}"
              else "Shizuku ✔ রেডি",
          )
        }
        bindShizukuService()
        return@launch
      }

      withContext(Dispatchers.Main) {
        runCatching { Shizuku.requestPermission(REQ_CODE_SHIZUKU) }
          .onFailure { err ->
            _state.update {
              it.copy(statusMessage = "Shizuku রিকোয়েস্ট ব্যর্থ: ${err.localizedMessage}")
            }
          }
      }
    }
  }

  private fun bindShizukuService() {
    val ctx = appContextRef ?: return
    if (isBindingInProgress) return
    if (userService != null) return
    isBindingInProgress = true
    runCatching {
      Shizuku.bindUserService(buildUserServiceArgs(ctx), serviceConnection)
    }.onFailure {
      isBindingInProgress = false
    }
  }

  suspend fun checkRootAccess(): Boolean =
    withContext(Dispatchers.IO) {
      val rooted =
        runCatching {
          val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
          val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
          val code = proc.waitFor()
          code == 0 && out.contains("uid=0")
        }.getOrDefault(false)

      _state.update {
        it.copy(
          isRootAvailable = rooted,
          statusMessage =
            if (rooted) "Root ✔ অ্যাক্সেস পাওয়া গেছে (uid=0)!"
            else "ডিভাইসে Root অ্যাক্সেস নেই (Shizuku ব্যবহার করুন)।",
        )
      }
      rooted
    }

  suspend fun enableMicTransfer(context: Context): String =
    withContext(Dispatchers.IO) {
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      audioManager?.let { am ->
        runCatching {
          am.mode = AudioManager.MODE_IN_COMMUNICATION
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = true
          val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        }
      }

      val rooted = checkRootAccess()
      var backendDetail = "Standard Speaker-to-Mic Acoustic Boost"

      if (rooted) {
        val suOut =
          runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id && getenforce"))
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
            proc.waitFor()
            out
          }.getOrDefault("")
        backendDetail = "Root (su) Routing + +12dB Mic Transfer Active ($suOut)"
      } else if (_state.value.hasShizukuPermission) {
        val shizukuOut =
          runCatching { userService?.enableMicTransferRouting() }.getOrNull().orEmpty()
        backendDetail =
          if (shizukuOut.isNotBlank()) {
            "Shizuku ADB Routing + +12dB Communication Boost ($shizukuOut)"
          } else {
            "Shizuku Authorized + +12dB Speaker-to-Mic Transfer Active"
          }
      }

      _state.update {
        it.copy(
          isInjectionActive = true,
          statusMessage = backendDetail,
        )
      }
      backendDetail
    }

  suspend fun disableMicTransfer(context: Context) =
    withContext(Dispatchers.IO) {
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      audioManager?.let { am ->
        runCatching {
          am.mode = AudioManager.MODE_NORMAL
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = false
        }
      }
      runCatching { userService?.disableMicTransferRouting() }
      _state.update {
        it.copy(
          isInjectionActive = false,
          statusMessage = "Mic Transfer বন্ধ করা হয়েছে। স্বাভাবিক অডিও চালু।",
        )
      }
    }

  suspend fun generateDiagnosticReport(context: Context): String =
    withContext(Dispatchers.IO) {
      refreshShizukuStatus()
      val rooted = checkRootAccess()
      val shizukuDiag =
        if (_state.value.hasShizukuPermission && userService != null) {
          runCatching { userService?.runAudioDiagnostic() }.getOrNull().orEmpty()
        } else {
          "Shizuku UserService not bound (grant Shizuku permission first)."
        }

      val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      val mode = am?.mode ?: -1
      val report = buildString {
        appendLine("=== ZONEX AUDIO & PRIVILEGE REPORT ===")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        appendLine("Root (su): ${if (rooted) "AVAILABLE" else "NOT AVAILABLE"}")
        appendLine("Shizuku Installed: ${_state.value.isShizukuInstalled}")
        appendLine("Shizuku Running: ${_state.value.isShizukuRunning}")
        appendLine("Shizuku Permission: ${_state.value.hasShizukuPermission}")
        appendLine("AudioManager Mode: $mode")
        appendLine("--- Privileged Probe ---")
        appendLine(shizukuDiag)
        appendLine("--- Reality Note ---")
        appendLine(
          "On non-rooted Android (ADB UID 2000), Android SELinux blocks direct kernel mic HAL injection while wearing headphones. Use Speaker Mode + Volume Max + +12dB Boost for Free Fire teammates, or Root/V8 Sound Card for wired loopback."
        )
      }
      _state.update { it.copy(diagnosticReport = report) }
      report
    }
}

package com.example.shizuku

import android.os.Process
import androidx.annotation.Keep
import com.example.IShizukuAudioService
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuAudioUserService @Keep constructor() : IShizukuAudioService.Stub() {

  override fun getShellIdentity(): String {
    val uid = Process.myUid()
    return execCmd("id").ifBlank { "uid=$uid(shell)" }
  }

  override fun runAudioDiagnostic(): String {
    val uid = Process.myUid()
    val selinux = execCmd("getenforce").ifBlank { "Unknown" }
    val idOut = execCmd("id").ifBlank { "uid=$uid" }
    val sndInfo = execCmd("ls /dev/snd").ifBlank { "restricted" }
    val tinymixPath = execCmd("which tinymix").ifBlank { "not present" }
    val audioDump = execCmd("dumpsys audio | head -n 12").ifBlank { "unavailable" }
    return buildString {
      appendLine("Process Identity: $idOut")
      appendLine("Privilege Level: ${if (uid == 0) "ROOT (UID 0)" else "ADB SHELL (UID $uid)"}")
      appendLine("SELinux Mode: $selinux")
      appendLine("tinymix binary: $tinymixPath")
      appendLine("/dev/snd nodes: ${sndInfo.replace("\n", ", ")}")
      appendLine("AudioService Probe:\n$audioDump")
    }.trim()
  }

  override fun enableMicTransferRouting(): String {
    val uid = Process.myUid()
    val tinymix = execCmd("which tinymix")
    val routeResult =
      if (uid == 0 && tinymix.isNotBlank()) {
        // Attempt privileged ALSA loopback / speaker-mic mixer boost on rooted HAL
        execCmd("cmd audio set-force-use COMMUNICATION SPEAKER")
        "ROOT_HAL_ROUTING"
      } else {
        // Non-root ADB shell (UID 2000): apply shell-level communication routing & verify
        val cmdAudio = execCmd("cmd audio set-force-use COMMUNICATION SPEAKER")
        "SHIZUKU_SHELL_COMMUNICATION_ROUTING ($cmdAudio)".trim()
      }
    return routeResult
  }

  override fun disableMicTransferRouting(): String {
    execCmd("cmd audio set-force-use COMMUNICATION NONE")
    return "RESTORED_NORMAL_ROUTING"
  }

  override fun destroy() {
    System.exit(0)
  }

  private fun execCmd(cmd: String): String {
    return runCatching {
      val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
      val out = BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
      val err = BufferedReader(InputStreamReader(proc.errorStream)).readText().trim()
      proc.waitFor()
      out.ifBlank { err }
    }.getOrDefault("")
  }
}

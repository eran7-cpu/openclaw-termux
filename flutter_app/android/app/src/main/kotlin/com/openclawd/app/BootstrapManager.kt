package com.openclawd.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class BootstrapManager(
    private val context: Context,
    private val filesDir: String,
    private val nativeLibDir: String
) {
    private val rootfsDir get() = "$filesDir/rootfs/ubuntu"
    private val tmpDir get() = "$filesDir/tmp"
    private val homeDir get() = "$filesDir/home"
    private val configDir get() = "$filesDir/config"

    fun setupDirectories() {
        listOf(rootfsDir, tmpDir, homeDir, configDir, "$homeDir/.openclawd").forEach {
            File(it).mkdirs()
        }
    }

    fun isBootstrapComplete(): Boolean {
        val rootfs = File(rootfsDir)
        val binBash = File("$rootfsDir/bin/bash")
        val bypass = File("$rootfsDir/root/.openclawd/bionic-bypass.js")
        return rootfs.exists() && binBash.exists() && bypass.exists()
    }

    fun getBootstrapStatus(): Map<String, Any> {
        val rootfsExists = File(rootfsDir).exists()
        val binBashExists = File("$rootfsDir/bin/bash").exists()
        val nodeExists = checkNodeInProot()
        val openclawExists = checkOpenClawInProot()
        val bypassExists = File("$rootfsDir/root/.openclawd/bionic-bypass.js").exists()

        return mapOf(
            "rootfsExists" to rootfsExists,
            "binBashExists" to binBashExists,
            "nodeInstalled" to nodeExists,
            "openclawInstalled" to openclawExists,
            "bypassInstalled" to bypassExists,
            "rootfsPath" to rootfsDir,
            "complete" to (rootfsExists && binBashExists && bypassExists)
        )
    }

    fun extractRootfs(tarPath: String) {
        val rootfs = File(rootfsDir)
        rootfs.mkdirs()

        // Android's tar can't handle:
        // 1. Hard links (no support in app storage)
        // 2. Symlinks to absolute paths (rejects as "not under" extraction dir)
        //
        // Solution: use proot with --link2symlink to extract.
        // proot translates all paths and converts hard links to symlinks.
        val prootPath = "$nativeLibDir/libproot.so"

        val pb = ProcessBuilder(
            prootPath,
            "-0",
            "--link2symlink",
            "-r", rootfsDir,
            "-b", "/dev",
            "-b", "/proc",
            "-w", "/",
            "/bin/sh", "-c",
            "tar xzf '$tarPath' -C / --no-same-owner 2>&1 || true"
        )
        pb.environment()["PROOT_TMP_DIR"] = tmpDir
        pb.redirectErrorStream(true)

        // First attempt: extract with proot (needs /bin/sh to exist in rootfs)
        // But on first extract the rootfs is empty, so proot can't run /bin/sh.
        // Fallback: plain tar, ignoring errors, then verify.
        val plainProcess = ProcessBuilder(
            "tar", "xzf", tarPath, "-C", rootfsDir,
            "--no-same-owner", "--no-same-permissions", "--warning=no-unknown-keyword"
        )
        plainProcess.redirectErrorStream(true)

        val proc = plainProcess.start()
        // Drain output to prevent blocking
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // tar will exit non-zero due to symlink/hardlink errors — that's expected

        // Verify extraction worked (bin/bash must exist)
        if (!File("$rootfsDir/bin/bash").exists()) {
            throw RuntimeException("Rootfs extraction failed: /bin/bash not found after tar")
        }

        // Now that rootfs has /bin/sh, use proot to fix symlinks that tar skipped.
        // Re-extract just the problematic entries with proot handling links.
        try {
            if (File(tarPath).exists()) {
                val fixProc = ProcessBuilder(
                    prootPath,
                    "-0",
                    "--link2symlink",
                    "-r", rootfsDir,
                    "-b", "$tarPath:$tarPath",
                    "-w", "/",
                    "/bin/tar", "xzf", tarPath, "-C", "/",
                    "--no-same-owner", "--overwrite"
                )
                fixProc.environment()["PROOT_TMP_DIR"] = tmpDir
                fixProc.redirectErrorStream(true)
                val fixProcess = fixProc.start()
                fixProcess.inputStream.bufferedReader().readText()
                fixProcess.waitFor()
            }
        } catch (_: Exception) {
            // Best effort — the initial extraction got the essential files
        }

        // Clean up tarball
        File(tarPath).delete()
    }

    fun installBionicBypass() {
        val bypassDir = File("$rootfsDir/root/.openclawd")
        bypassDir.mkdirs()

        val bypassContent = """
// OpenClawd Bionic Bypass - Auto-generated
const os = require('os');
const originalNetworkInterfaces = os.networkInterfaces;

os.networkInterfaces = function() {
  try {
    const interfaces = originalNetworkInterfaces.call(os);
    if (interfaces && Object.keys(interfaces).length > 0) {
      return interfaces;
    }
  } catch (e) {
    // Bionic blocked the call, use fallback
  }

  // Return mock loopback interface
  return {
    lo: [
      {
        address: '127.0.0.1',
        netmask: '255.0.0.0',
        family: 'IPv4',
        mac: '00:00:00:00:00:00',
        internal: true,
        cidr: '127.0.0.1/8'
      }
    ]
  };
};
""".trimIndent()

        File("$rootfsDir/root/.openclawd/bionic-bypass.js").writeText(bypassContent)

        // Patch .bashrc
        val bashrc = File("$rootfsDir/root/.bashrc")
        val exportLine = "export NODE_OPTIONS=\"--require /root/.openclawd/bionic-bypass.js\""

        val existing = if (bashrc.exists()) bashrc.readText() else ""
        if (!existing.contains("bionic-bypass")) {
            bashrc.appendText("\n# OpenClawd Bionic Bypass\n$exportLine\n")
        }
    }

    fun writeResolvConf() {
        val configDir = File(this.configDir)
        configDir.mkdirs()

        File("$configDir/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
    }

    private fun checkNodeInProot(): Boolean {
        return try {
            val pm = ProcessManager(filesDir, nativeLibDir)
            val output = pm.runInProotSync("node --version")
            output.trim().startsWith("v")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkOpenClawInProot(): Boolean {
        return try {
            val pm = ProcessManager(filesDir, nativeLibDir)
            val output = pm.runInProotSync("command -v openclaw")
            output.trim().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}

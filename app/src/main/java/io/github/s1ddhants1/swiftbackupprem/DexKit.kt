@file:JvmName("DexKit")

package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

data class VersionClasses(
    val clientId: String,
    val backupApk: String,
    val paths: String,
    val homeViewModel: String,
    val authUser: String,
    val anonUser: String
)

val versionMap = mapOf(
    561 to VersionClasses("kf.s0", "org.swiftapps.swiftbackup.common.w1", "me.b", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    569 to VersionClasses("rf.r0", "org.swiftapps.swiftbackup.common.n2", "te.c", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    590 to VersionClasses("eh.u", "org.swiftapps.swiftbackup.common.c2", "org.swiftapps.swiftbackup.a", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    620 to VersionClasses("defpackage.gn5", "defpackage.qm", "defpackage.ry5", "defpackage.c64", "defpackage.d45", "defpackage.b45"),
)

@Keep
@JvmField
var clientIdClass: Class<*>? = null
@Keep
@JvmField
var backupApkClass: Class<*>? = null
@Keep
@JvmField
var pathsClass: Class<*>? = null
@Keep
@JvmField
var vClass: Class<*>? = null
@Keep
@JvmField
var cloudGmsClass: Class<*>? = null
@Keep
@JvmField
var homeViewModelClass: Class<*>? = null
@Keep
@JvmField
var authUserClass: Class<*>? = null
@Keep
@JvmField
var anonUserClass: Class<*>? = null

private fun hasResolvedHookTargets(): Boolean =
    listOf(
        clientIdClass,
        backupApkClass,
        pathsClass,
        vClass,
        homeViewModelClass,
        authUserClass
    ).all { it != null }

@Keep
@Suppress("DEPRECATION")
fun findObfuscatedClasses(ctx: Context, cl: ClassLoader, sourceDir: String) {
    val ver = Integer.valueOf(ctx.packageManager.getPackageInfo(Consts.packageName, 0).versionCode)
    versionMap[ver]?.let { classes ->
        attempt("load clientIdClass from versionMap", silent = true) { clientIdClass = cl.loadClass(classes.clientId) }
        attempt("load backupApkClass from versionMap", silent = true) { backupApkClass = cl.loadClass(classes.backupApk) }
        attempt("load pathsClass from versionMap", silent = true) { pathsClass = cl.loadClass(classes.paths) }
        attempt("load homeViewModelClass from versionMap", silent = true) { homeViewModelClass = cl.loadClass(classes.homeViewModel) }
        attempt("load authUserClass from versionMap", silent = true) { authUserClass = cl.loadClass(classes.authUser) }
        attempt("load anonUserClass from versionMap", silent = true) { anonUserClass = cl.loadClass(classes.anonUser) }
    }

    attempt("load V class fallback", silent = true) {
        vClass = cl.loadClass("org.swiftapps.swiftbackup.common.V")
    }

    for (name in listOf("org.swiftapps.swiftbackup.cloud.d0", "org.swiftapps.swiftbackup.cloud.d")) {
        val loaded = attempt("load cloud Gms class ($name)", silent = true) {
            cl.loadClass(name)
        }
        if (loaded != null) {
            cloudGmsClass = loaded
            break
        }
    }

    if (hasResolvedHookTargets()) {
        Log.d("SBP", "Resolved Swift Backup hook classes without DexKit scan")
        return
    }

    attempt("load dexkit native library") {
        System.loadLibrary("dexkit")
    }

    val excludePackages = listOf("android", "androidx", "com", "iammert", "java", "javax", "kotlin", "kotlinx", "moe", "nz.mega",
        "okhttp3", "okio", "retrofit", "rikka")

    try {
        DexKitBridge.create(sourceDir).use { bridge ->
            if (clientIdClass == null) {
                val oauthCandidates = bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("org.swiftapps.swiftbackup:/oauth")
                    }
                }
                Log.d("SBP", "Found ${oauthCandidates.size} candidate(s) for clientIdClass by oauth string")
                val selected = when (oauthCandidates.size) {
                    0 -> {
                        val structuralCandidates = bridge.findClass {
                            excludePackages(excludePackages)
                            matcher {
                                fields {
                                    add {
                                        modifiers(Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL)
                                        name("a")
                                    }
                                    add {
                                        modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL)
                                        name("b")
                                    }
                                    add {
                                        modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL)
                                        name("c")
                                        type("java.lang.String")
                                    }
                                    add {
                                        modifiers(Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL)
                                        name("d")
                                        type("android.net.Uri")
                                    }
                                    count(4)
                                }
                                addMethod {
                                    modifiers(Modifier.PUBLIC or Modifier.FINAL)
                                    returnType("android.content.Intent")
                                    name("f")
                                    addParamType("boolean")
                                }
                            }
                        }
                        Log.d("SBP", "Found ${structuralCandidates.size} candidate(s) for clientIdClass by structure")
                        structuralCandidates.singleOrNull() ?: structuralCandidates.firstOrNull()
                    }
                    1 -> oauthCandidates.single()
                    else -> {
                        Log.w("SBP", "Multiple clientIdClass candidates (${oauthCandidates.size}): ${oauthCandidates.map { it.name }}")
                        oauthCandidates.firstOrNull()
                    }
                }
                selected?.let {
                    clientIdClass = it.getInstance(cl)
                    Log.d("SBP", "Found client id class: ${it.name}")
                }
            }

            if (backupApkClass == null) {
                val candidates = bridge.findClass {
                    matcher {
                        usingStrings("swift_backup_apks/", "SwiftBackupApkSaver")
                    }
                }
                Log.d("SBP", "Found ${candidates.size} candidate(s) for backupApkClass")
                val selected = when (candidates.size) {
                    0 -> null
                    1 -> candidates.single()
                    else -> {
                        Log.w("SBP", "Multiple backupApkClass candidates (${candidates.size}): ${candidates.map { it.name }}")
                        candidates.firstOrNull()
                    }
                }
                selected?.let {
                    backupApkClass = it.getInstance(cl)
                    Log.d("SBP", "Found backup apk class: ${it.name}")
                }
            }

            if (pathsClass == null) {
                val candidates = bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        methods {
                            add {
                                name("<init>")
                                addParamType("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
                                addParamType("java.lang.String")
                                paramCount(2)
                                usingStrings("accounts/", "backups/", "cache/", "apps/", "local/", "cloud/", "icon_cache/", "sms/", "calls/")
                            }
                        }
                    }
                }
                Log.d("SBP", "Found ${candidates.size} candidate(s) for pathsClass")
                val selected = candidates.singleOrNull() ?: candidates.firstOrNull()
                selected?.let {
                    pathsClass = it.getInstance(cl)
                    Log.d("SBP", "Found paths class: ${it.name}")
                }
            }

            if (vClass == null) {
                val candidates = bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("f4s6woi0e98")
                    }
                }
                Log.d("SBP", "Found ${candidates.size} candidate(s) for vClass")
                val selected = when (candidates.size) {
                    0 -> null
                    1 -> candidates.single()
                    else -> {
                        Log.w("SBP", "Multiple vClass candidates (${candidates.size}): ${candidates.map { it.name }}")
                        candidates.firstOrNull()
                    }
                }
                selected?.let {
                    vClass = it.getInstance(cl)
                    Log.d("SBP", "Found V class: ${it.name}")
                }
            }

            if (cloudGmsClass == null) {
                val candidates = bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("nogms_access_token")
                    }
                }
                Log.d("SBP", "Found ${candidates.size} candidate(s) for cloudGmsClass")
                val selected = when (candidates.size) {
                    0 -> null
                    1 -> candidates.single()
                    else -> {
                        Log.w("SBP", "Multiple cloudGmsClass candidates (${candidates.size}): ${candidates.map { it.name }}")
                        candidates.firstOrNull()
                    }
                }
                selected?.let {
                    cloudGmsClass = it.getInstance(cl)
                    Log.d("SBP", "Found cloud GMS class: ${it.name}")
                }
            }

            if (homeViewModelClass == null) {
                val candidates1 = bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("setup_cloud_first_startup", "KEY_SCHEDULE_ENABLED")
                    }
                }
                val filtered1 = candidates1.filter { !it.name.contains("$") && !it.name.contains("AlarmReceiver") }
                Log.d("SBP", "Found ${candidates1.size} candidate(s) (${filtered1.size} filtered) for homeViewModelClass (primary)")

                val selected = if (filtered1.isNotEmpty()) {
                    if (filtered1.size > 1) {
                        Log.w("SBP", "Multiple homeViewModelClass primary candidates: ${filtered1.map { it.name }}")
                    }
                    filtered1.first()
                } else {
                    val candidates2 = bridge.findClass {
                        excludePackages(excludePackages)
                        matcher {
                            usingStrings("checkCloudConnectPromptNeeded=")
                        }
                    }
                    val filtered2 = candidates2.filter { !it.name.contains("$") }
                    Log.d("SBP", "Found ${candidates2.size} candidate(s) (${filtered2.size} filtered) for homeViewModelClass (fallback)")
                    if (filtered2.size > 1) {
                        Log.w("SBP", "Multiple homeViewModelClass fallback candidates: ${filtered2.map { it.name }}")
                    }
                    filtered2.firstOrNull()
                }

                selected?.let {
                    homeViewModelClass = it.getInstance(cl)
                    Log.d("SBP", "Found HomeViewModel class: ${it.name}")
                }
            }

            if (authUserClass == null) {
                val candidates = bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        addMethod {
                            returnType("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
                            modifiers(Modifier.PUBLIC or Modifier.STATIC)
                            paramCount(0)
                        }
                    }
                }
                val filtered = candidates.filter { !it.name.contains("$") }
                Log.d("SBP", "Found ${candidates.size} candidate(s) (${filtered.size} filtered) for authUserClass")
                val selected = when (filtered.size) {
                    0 -> null
                    1 -> filtered.single()
                    else -> {
                        Log.w("SBP", "Multiple authUserClass candidates (${filtered.size}): ${filtered.map { it.name }}")
                        filtered.first()
                    }
                }
                selected?.let {
                    authUserClass = it.getInstance(cl)
                    Log.d("SBP", "Found AuthUser class: ${it.name}")
                }
            }
        }
    } catch (t: Throwable) {
        Log.e("SBP", "DexKit search encountered an error", t)
    }

    if (clientIdClass == null || backupApkClass == null || pathsClass == null || homeViewModelClass == null) {
        Log.w("SBP", "Couldn't fully hook Swift Backup.")
    }
}

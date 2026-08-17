package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.versionMap
import org.luckypray.dexkit.DexKitBridge
import android.annotation.SuppressLint
import java.lang.reflect.Modifier

@Keep
object TargetClassResolver {

    @SuppressLint("NonUniqueDexKitData")
    @Suppress("DEPRECATION")
    fun resolve(ctx: Context, cl: ClassLoader, sourceDir: String): ResolvedTargets {
        var clientId: Class<*>? = null
        var v: Class<*>? = null
        var cloudGms: Class<*>? = null
        var homeVm: Class<*>? = null
        var authUser: Class<*>? = null
        var anonUser: Class<*>? = null

        val ver = Integer.valueOf(ctx.packageManager.getPackageInfo(Consts.packageName, 0).versionCode)
        versionMap[ver]?.let { classes ->
            attempt("load clientIdClass from versionMap", silent = true) { clientId = cl.loadClass(classes.clientId) }
            attempt("load homeViewModelClass from versionMap", silent = true) { homeVm = cl.loadClass(classes.homeViewModel) }
            attempt("load authUserClass from versionMap", silent = true) { authUser = cl.loadClass(classes.authUser) }
            attempt("load anonUserClass from versionMap", silent = true) { anonUser = cl.loadClass(classes.anonUser) }
        }

        attempt("load V class fallback", silent = true) {
            v = cl.loadClass("org.swiftapps.swiftbackup.common.V")
        }

        for (name in listOf("org.swiftapps.swiftbackup.cloud.d0", "org.swiftapps.swiftbackup.cloud.d")) {
            val loaded = attempt("load cloud Gms class ($name)", silent = true) {
                cl.loadClass(name)
            }
            if (loaded != null) {
                cloudGms = loaded
                break
            }
        }

        if (clientId != null && v != null && homeVm != null && authUser != null) {
            Log.d("SBP", "Resolved Swift Backup hook classes without DexKit scan")
            return ResolvedTargets(clientId, v, cloudGms, homeVm, authUser, anonUser)
        }

        attempt("load dexkit native library") {
            System.loadLibrary("dexkit")
        }

        val excludePackages = listOf(
            "android", "androidx", "com", "iammert", "java", "javax", "kotlin", "kotlinx", "moe", "nz.mega",
            "okhttp3", "okio", "retrofit", "rikka"
        )

        try {
            DexKitBridge.create(sourceDir).use { bridge ->
                if (clientId == null) {
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
                        clientId = it.getInstance(cl)
                        Log.d("SBP", "Found client id class: ${it.name}")
                    }
                }

                if (v == null) {
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
                        v = it.getInstance(cl)
                        Log.d("SBP", "Found V class: ${it.name}")
                    }
                }

                if (cloudGms == null) {
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
                        cloudGms = it.getInstance(cl)
                        Log.d("SBP", "Found cloud GMS class: ${it.name}")
                    }
                }

                if (homeVm == null) {
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
                        homeVm = it.getInstance(cl)
                        Log.d("SBP", "Found HomeViewModel class: ${it.name}")
                    }
                }

                if (authUser == null) {
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
                        authUser = it.getInstance(cl)
                        Log.d("SBP", "Found AuthUser class: ${it.name}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("SBP", "DexKit search encountered an error", t)
        }

        if (clientId == null || homeVm == null) {
            Log.w("SBP", "Couldn't fully hook Swift Backup.")
        }

        return ResolvedTargets(clientId, v, cloudGms, homeVm, authUser, anonUser)
    }
}

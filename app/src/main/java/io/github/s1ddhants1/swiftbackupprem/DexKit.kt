@file:JvmName("DexKit")

package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

private val classesClientId = mapOf(561 to "kf.s0", 569 to "rf.r0", 590 to "eh.u", 620 to "defpackage.gn5")
private val classesBackupApk = mapOf(561 to "org.swiftapps.swiftbackup.common.w1", 569 to "org.swiftapps.swiftbackup.common.n2", 590 to "org.swiftapps.swiftbackup.common.c2", 620 to "defpackage.qm")
private val classesPaths = mapOf(561 to "me.b", 569 to "te.c", 590 to "org.swiftapps.swiftbackup.a", 620 to "defpackage.ry5")
private val classesHomeViewModel = mapOf(561 to "org.swiftapps.swiftbackup.home.a", 569 to "org.swiftapps.swiftbackup.home.a", 590 to "org.swiftapps.swiftbackup.home.a", 620 to "defpackage.c64")
private val classesAuthUser = mapOf(561 to "org.swiftapps.swiftbackup.common.a3", 569 to "org.swiftapps.swiftbackup.common.a3", 590 to "org.swiftapps.swiftbackup.common.a3", 620 to "defpackage.d45")
private val classesAnonUser = mapOf(561 to "org.swiftapps.swiftbackup.anonymous.a", 569 to "org.swiftapps.swiftbackup.anonymous.a", 590 to "org.swiftapps.swiftbackup.anonymous.a", 620 to "defpackage.b45")

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

@Keep
@Suppress("DEPRECATION")
fun findObfuscatedClasses(ctx: Context, cl: ClassLoader, sourceDir: String) {
    val ver = Integer.valueOf(ctx.packageManager.getPackageInfo(Consts.packageName, 0).versionCode)
    if (classesClientId.containsKey(ver)) {
        try { clientIdClass = cl.loadClass(classesClientId[ver]) } catch (_: Throwable) {}
        try { backupApkClass = cl.loadClass(classesBackupApk[ver]) } catch (_: Throwable) {}
        try { pathsClass = cl.loadClass(classesPaths[ver]) } catch (_: Throwable) {}
        try { homeViewModelClass = cl.loadClass(classesHomeViewModel[ver]) } catch (_: Throwable) {}
        try { authUserClass = cl.loadClass(classesAuthUser[ver]) } catch (_: Throwable) {}
        try { anonUserClass = cl.loadClass(classesAnonUser[ver]) } catch (_: Throwable) {}
    }

    try {
        vClass = cl.loadClass("org.swiftapps.swiftbackup.common.V")
    } catch (_: Throwable) {}

    for (name in listOf("org.swiftapps.swiftbackup.cloud.d0", "org.swiftapps.swiftbackup.cloud.d")) {
        try {
            cloudGmsClass = cl.loadClass(name)
            break
        } catch (_: Throwable) {}
    }

    try {
        System.loadLibrary("dexkit")
    } catch (t: Throwable) {
        Log.e("SBP", "Failed loading dexkit library", t)
    }

    val excludePackages = listOf("android", "androidx", "com", "iammert", "java", "javax", "kotlin", "kotlinx", "moe", "nz.mega",
        "okhttp3", "okio", "retrofit", "rikka")

    try {
        DexKitBridge.create(sourceDir).use { bridge ->
            if (clientIdClass == null) {
                (bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("org.swiftapps.swiftbackup:/oauth")
                    }
                }.firstOrNull() ?: bridge.findClass {
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
                }.singleOrNull())?.let {
                    clientIdClass = it.getInstance(cl)
                    Log.d("SBP", "Found client id class: ${it.name}")
                }
            }

            if (backupApkClass == null) {
                (bridge.findClass {
                    matcher {
                        usingStrings("swift_backup_apks/", "SwiftBackupApkSaver")
                    }
                }.firstOrNull())?.let {
                    backupApkClass = it.getInstance(cl)
                    Log.d("SBP", "Found backup apk class: ${it.name}")
                }
            }

            if (pathsClass == null) {
                (bridge.findClass {
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
                }.singleOrNull())?.let {
                    pathsClass = it.getInstance(cl)
                    Log.d("SBP", "Found paths class: ${it.name}")
                }
            }

            if (vClass == null) {
                (bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("f4s6woi0e98")
                    }
                }.firstOrNull())?.let {
                    vClass = it.getInstance(cl)
                    Log.d("SBP", "Found V class: ${it.name}")
                }
            }

            if (cloudGmsClass == null) {
                (bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("nogms_access_token")
                    }
                }.firstOrNull())?.let {
                    cloudGmsClass = it.getInstance(cl)
                    Log.d("SBP", "Found cloud GMS class: ${it.name}")
                }
            }

            if (homeViewModelClass == null) {
                (bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        usingStrings("setup_cloud_first_startup", "KEY_SCHEDULE_ENABLED")
                    }
                }.firstOrNull { !it.name.contains("$") && !it.name.contains("AlarmReceiver") }
                    ?: bridge.findClass {
                        excludePackages(excludePackages)
                        matcher {
                            usingStrings("checkCloudConnectPromptNeeded=")
                        }
                    }.firstOrNull { !it.name.contains("$") })?.let {
                    homeViewModelClass = it.getInstance(cl)
                    Log.d("SBP", "Found HomeViewModel class: ${it.name}")
                }
            }

            if (authUserClass == null) {
                (bridge.findClass {
                    excludePackages(excludePackages)
                    matcher {
                        addMethod {
                            returnType("org.swiftapps.swiftbackup.anonymous.MFirebaseUser")
                            modifiers(Modifier.PUBLIC or Modifier.STATIC)
                            paramCount(0)
                        }
                    }
                }.firstOrNull { !it.name.contains("$") })?.let {
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

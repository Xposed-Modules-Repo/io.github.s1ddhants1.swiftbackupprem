@file:JvmName("DexKit")

package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import androidx.annotation.Keep
import io.github.s1ddhants1.swiftbackupprem.hook.TargetClassResolver

data class VersionClasses(
    val clientId: String,
    val homeViewModel: String,
    val authUser: String,
    val anonUser: String
)

val versionMap = mapOf(
    561 to VersionClasses("kf.s0", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    569 to VersionClasses("rf.r0", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    590 to VersionClasses("eh.u", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    620 to VersionClasses("defpackage.gn5", "defpackage.c64", "defpackage.d45", "defpackage.b45"),
)

@Keep
@JvmField
var clientIdClass: Class<*>? = null
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
fun findObfuscatedClasses(ctx: Context, cl: ClassLoader, sourceDir: String) {
    val targets = TargetClassResolver.resolve(ctx, cl, sourceDir)
    clientIdClass = targets.clientIdClass
    vClass = targets.vClass
    cloudGmsClass = targets.cloudGmsClass
    homeViewModelClass = targets.homeViewModelClass
    authUserClass = targets.authUserClass
    anonUserClass = targets.anonUserClass
}

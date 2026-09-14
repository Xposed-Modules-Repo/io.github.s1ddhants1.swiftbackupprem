@file:JvmName("DexKit")

package io.github.s1ddhants1.swiftbackupprem

data class VersionClasses(
    val clientId: String,
    val homeViewModel: String,
    val authUser: String,
    val anonUser: String,
    val oauthHelper: String? = null,
    val authRequestBuilder: String? = null,
    val appBackup: String? = null,
    val appMetadataXml: String? = null,
    val firebaseWatcher: String? = null,
    val fireSynchronizer: String? = null,
    val fireSynchronizerSuccess: String? = null,
    val customClassMapper: String? = null,
    val settingsFragment: String? = null,
    val settingsDetailFragment: String? = null,
    val baseSettingsFragment: String? = null
)

val versionMap = mapOf(
    561 to VersionClasses("kf.s0", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    569 to VersionClasses("rf.r0", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    590 to VersionClasses("eh.u", "org.swiftapps.swiftbackup.home.a", "org.swiftapps.swiftbackup.common.a3", "org.swiftapps.swiftbackup.anonymous.a"),
    620 to VersionClasses(
        clientId = "defpackage.gn5",
        homeViewModel = "defpackage.c64",
        authUser = "defpackage.d45",
        anonUser = "defpackage.b45",
        oauthHelper = "defpackage.uj",
        authRequestBuilder = "defpackage.c90",
        appBackup = "defpackage.hk",
        appMetadataXml = "defpackage.cu",
        firebaseWatcher = "defpackage.gg3",
        fireSynchronizer = "defpackage.cf3",
        fireSynchronizerSuccess = "defpackage.xe3",
        customClassMapper = "defpackage.t62",
        settingsFragment = "defpackage.pa7",
        settingsDetailFragment = "org.swiftapps.swiftbackup.settings.a",
        baseSettingsFragment = "defpackage.fm0"
    ),
)

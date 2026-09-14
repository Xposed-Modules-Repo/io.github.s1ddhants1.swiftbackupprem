package io.github.s1ddhants1.swiftbackupprem.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import androidx.recyclerview.widget.RecyclerView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.GoogleServicesJson
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import io.github.s1ddhants1.swiftbackupprem.util.loadClassFlexible
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@Keep
object InAppSettingsHook : HookHandler {

    const val SBP_CATEGORY_ID = 999
    const val PREF_KEY_SBP = "swiftbackupprem_settings"
    const val PREF_KEY_IMPORT_JSON = "pref_import_google_services"
    const val REQUEST_CODE_PICK_JSON = 48701

    @Volatile
    var activePrefs: PreferencesManager? = null

    @Volatile
    private var activeImportPrefRef: WeakReference<Any>? = null

    var activeImportPref: Any?
        get() = activeImportPrefRef?.get()
        set(value) {
            activeImportPrefRef = value?.let { WeakReference(it) }
        }

    private fun findCreatePreferencesMethod(fragmentClass: Class<*>): Method? {
        var curr: Class<*>? = fragmentClass
        while (curr != null && curr != Any::class.java) {
            val abs = curr.declaredMethods.firstOrNull {
                Modifier.isAbstract(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == java.lang.Void.TYPE
            }
            if (abs != null) {
                return try {
                    fragmentClass.getDeclaredMethod(abs.name)
                } catch (_: Throwable) { null }
            }
            curr = curr.superclass
        }
        return fragmentClass.declaredMethods.firstOrNull {
            it.parameterCount == 0 &&
                it.returnType == java.lang.Void.TYPE &&
                (it.name == "l" || it.name == "onCreatePreferences")
        }
    }

    private fun findPreferenceClickMethod(fragmentClass: Class<*>, prefClass: Class<*>): Method? {
        for (iface in fragmentClass.interfaces) {
            val m = iface.declaredMethods.firstOrNull {
                it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(prefClass) &&
                    (it.returnType == java.lang.Boolean.TYPE || it.returnType == Boolean::class.javaObjectType)
            }
            if (m != null) {
                return try {
                    fragmentClass.getDeclaredMethod(m.name, m.parameterTypes[0])
                } catch (_: Throwable) { null }
            }
        }
        return fragmentClass.declaredMethods.firstOrNull {
            it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(prefClass) &&
                (it.returnType == java.lang.Boolean.TYPE || it.returnType == Boolean::class.javaObjectType)
        }
    }

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        activePrefs = prefs

        val prefClass = classLoader.loadClass("androidx.preference.Preference")
        val helper = HostPreferenceHelper(classLoader, context, targets.baseSettingsFragmentClass)

        val settingsFragmentClass = targets.settingsFragmentClass
        val settingsDetailFragmentClass = targets.settingsDetailFragmentClass
            ?: loadClassFlexible(classLoader, "org.swiftapps.swiftbackup.settings.a")

        val baseFragmentClass = targets.baseSettingsFragmentClass
            ?: settingsFragmentClass?.superclass
            ?: settingsDetailFragmentClass?.superclass

        attempt("hook Settings fragment onCreatePreferences", silent = true) {
            val sfc = settingsFragmentClass ?: return@attempt
            val lMethod = findCreatePreferencesMethod(sfc) ?: return@attempt

            module.hookTracked(
                lMethod,
                idPrefix = "sbp-settings-sfc-create",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject
                if (fragment != null) {
                    attempt("inject SBP entry from settings fragment") {
                        val screen = getPreferenceScreen(fragment)
                        val ctx = attempt("get requireContext", silent = true) {
                            fragment.javaClass.getMethod("requireContext").invoke(fragment) as? Context
                        }
                        if (screen != null && ctx != null) {
                            val hostHelper = HostPreferenceHelper(classLoader, ctx, baseFragmentClass)
                            hostHelper.injectSettingsEntry(screen, ctx, fragment)
                        }
                    }
                }
                result
            }
        }

        attempt("hook Settings fragment click interception") {
            val sfc = settingsFragmentClass ?: return@attempt
            val dMethod = findPreferenceClickMethod(sfc, prefClass) ?: return@attempt

            module.hookTracked(
                dMethod,
                idPrefix = "sbp-settings-sfc-click",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val pref = chain.args[0]
                val key = helper.getKey(pref)
                if (key == PREF_KEY_SBP) {
                    val fragment = chain.thisObject
                    val act = attempt("get act", silent = true) {
                        fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                    } ?: (fragment.javaClass.getMethod("requireContext").invoke(fragment) as Context)
                    Log.i(Consts.TAG, "SwiftBackupPrem preference clicked! Opening SettingsDetailActivity category=$SBP_CATEGORY_ID")
                    val detailCls = classLoader.loadClass("org.swiftapps.swiftbackup.settings.SettingsDetailActivity")
                    val intent = Intent(act, detailCls).apply {
                        putExtra("category", SBP_CATEGORY_ID)
                        putExtra("category_title", "SwiftBackupPrem")
                    }
                    act.startActivity(intent)
                    return@intercept true
                }
                chain.proceed()
            }
        }

        attempt("hook SettingsDetail fragment onCreatePreferences", silent = true) {
            val sdfc = settingsDetailFragmentClass ?: return@attempt
            val lMethod = findCreatePreferencesMethod(sdfc) ?: return@attempt

            module.hookTracked(
                lMethod,
                idPrefix = "sbp-settings-detail-create",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject
                if (fragment != null) {
                    attempt("populate SBP native settings page from detail fragment") {
                        val activity = fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                        val category = activity?.intent?.getIntExtra("category", 0) ?: 0
                        if (activity != null && category == SBP_CATEGORY_ID) {
                            val screen = getPreferenceScreen(fragment)
                            if (screen != null) {
                                val hostHelper = HostPreferenceHelper(classLoader, activity, baseFragmentClass)
                                hostHelper.clearScreen(screen)
                                hostHelper.populateSbpSettings(
                                    screen,
                                    activity,
                                    fragment,
                                    activePrefs ?: PreferencesManager().also { it.loadFromFallbackStorage(activity) }
                                )
                                val rv = findRecyclerView(fragment)
                                rv?.itemAnimator = null
                                refreshRecyclerAdapter(fragment)
                            }
                        }
                    }
                }
                result
            }
        }

        attempt("hook SettingsDetail fragment click for SBP items") {
            val sdfc = settingsDetailFragmentClass ?: return@attempt
            val dMethod = findPreferenceClickMethod(sdfc, prefClass) ?: return@attempt

            module.hookTracked(
                dMethod,
                idPrefix = "sbp-settings-detail-click",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                val fragment = chain.thisObject
                val activity = fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                val category = activity?.intent?.getIntExtra("category", 0) ?: 0
                if (activity != null && category == SBP_CATEGORY_ID) {
                    val pref = chain.args[0]
                    val key = helper.getKey(pref)
                    val handled = handleSbpClick(pref, key, activity, helper)
                    if (handled) return@intercept true
                }
                chain.proceed()
            }
        }

        attempt("hook baseFragment onViewCreated to disable ItemAnimator", silent = true) {
            val bfc = baseFragmentClass ?: return@attempt
            val onViewCreatedMethod = bfc.getMethod("onViewCreated", android.view.View::class.java, Bundle::class.java)

            module.hookTracked(
                onViewCreatedMethod,
                idPrefix = "sbp-baseFragment-onViewCreated",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject
                if (fragment != null) {
                    attempt("disable item animator on fragment RecyclerView", silent = true) {
                        val rv = findRecyclerView(fragment)
                        rv?.itemAnimator = null
                        Log.i(Consts.TAG, "Disabled ItemAnimator on settings RecyclerView")
                    }
                }
                result
            }
        }

        attempt("hook RecyclerView.onLayout to prevent animation NPE crash", silent = true) {
            val rvClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val onLayoutMethod = rvClass.getDeclaredMethod(
                "onLayout",
                java.lang.Boolean.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Integer.TYPE
            )
            module.hookTracked(
                onLayoutMethod,
                idPrefix = "sbp-rv-onLayout-safe",
                priority = XposedInterface.PRIORITY_HIGHEST,
                deoptimize = true
            ).intercept { chain ->
                try {
                    chain.proceed()
                } catch (e: NullPointerException) {
                    Log.w(Consts.TAG, "Safely intercepted RecyclerView onLayout NPE: ${e.message}")
                }
            }
        }

        attempt("hook RecyclerView.setAdapter to disable itemAnimator", silent = true) {
            val rvClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val setAdapterMethod = rvClass.declaredMethods.firstOrNull {
                it.name == "setAdapter" && it.parameterCount == 1
            } ?: return@attempt

            module.hookTracked(
                setAdapterMethod,
                idPrefix = "sbp-rv-setAdapter",
                priority = XposedInterface.PRIORITY_DEFAULT,
                deoptimize = true
            ).intercept { chain ->
                val rv = chain.thisObject as? RecyclerView
                val result = chain.proceed()
                rv?.itemAnimator = null
                result
            }
        }

        attempt("hook onActivityResult for JSON picker", silent = true) {
            val detailCls = try {
                classLoader.loadClass("org.swiftapps.swiftbackup.settings.SettingsDetailActivity")
            } catch (_: Throwable) {
                null
            }
            val targetClasses = mutableListOf<Class<*>>()
            var c: Class<*>? = detailCls
            while (c != null && c != Any::class.java) {
                try {
                    c.getDeclaredMethod(
                        "onActivityResult",
                        java.lang.Integer.TYPE,
                        java.lang.Integer.TYPE,
                        Intent::class.java
                    )
                    targetClasses.add(c)
                } catch (_: NoSuchMethodException) {}
                c = c.superclass
            }
            if (targetClasses.isEmpty()) {
                targetClasses.add(classLoader.loadClass("android.app.Activity"))
            }

            for (cls in targetClasses) {
                try {
                    val m = cls.getDeclaredMethod(
                        "onActivityResult",
                        java.lang.Integer.TYPE,
                        java.lang.Integer.TYPE,
                        Intent::class.java
                    )
                    module.hookTracked(
                        m,
                        idPrefix = "sbp-on-result-${cls.simpleName}",
                        priority = XposedInterface.PRIORITY_HIGHEST,
                        deoptimize = true
                    ).intercept { chain ->
                        val reqCode = chain.args[0] as? Int ?: 0
                        val resCode = chain.args[1] as? Int ?: 0
                        val data = chain.args[2] as? Intent
                        if (reqCode == REQUEST_CODE_PICK_JSON && resCode == Activity.RESULT_OK && data?.data != null) {
                            val act = chain.thisObject as? Activity
                            val uri = data.data
                            if (act != null && uri != null) {
                                handleImportedUri(act, uri)
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun handleSbpClick(pref: Any, key: String?, activity: Activity, helper: HostPreferenceHelper): Boolean {
        val p = activePrefs ?: PreferencesManager().also { it.loadFromFallbackStorage(activity) }
        activePrefs = p

        if (helper.isTwoStatePreference(pref) && key != null) {
            val newChecked = helper.isChecked(pref)

            when (key) {
                "enable_premium" -> p.enablePremium = newChecked
                "disable_telemetry" -> p.disableTelemetry = newChecked
                "unlock_local_cloud_features" -> p.unlockLocalCloudFeatures = newChecked
                "custom_firebase_app" -> p.customFirebaseApp = newChecked
            }
            p.saveToFallbackStorage(activity)
            Log.i(Consts.TAG, "Toggled $key -> $newChecked and saved")
            return true
        }

        when (key) {
            PREF_KEY_IMPORT_JSON -> {
                launchJsonFilePicker(activity, pref)
                return true
            }
            "pref_link_telegram" -> {
                attempt("open telegram link", silent = true) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SwiftBackupPrem")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }
                return true
            }
            "pref_link_github" -> {
                attempt("open github link", silent = true) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/s1ddhants1/SwiftBackupPrem")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }
                return true
            }
        }

        return false
    }

    private fun launchJsonFilePicker(activity: Activity, pref: Any) {
        activeImportPref = pref
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "*/*"))
        }
        try {
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_JSON)
        } catch (_: Throwable) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "*/*"))
                }
                activity.startActivityForResult(fallbackIntent, REQUEST_CODE_PICK_JSON)
            } catch (e: Throwable) {
                Toast.makeText(activity, "Unable to launch file picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleImportedUri(activity: Activity, uri: Uri) {
        attempt("handle imported json uri") {
            val jsonString = activity.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            }
            if (jsonString.isNullOrBlank()) {
                Toast.makeText(activity, "Failed to read file from picker", Toast.LENGTH_SHORT).show()
                return@attempt
            }
            val p = activePrefs ?: PreferencesManager().also { it.loadFromFallbackStorage(activity) }
            activePrefs = p
            applyGoogleServicesJsonString(activity, p, jsonString, activeImportPref)
        }
    }

    private fun applyGoogleServicesJsonString(
        activity: Activity,
        prefs: PreferencesManager,
        jsonString: String,
        targetPref: Any? = null
    ): Boolean {
        return try {
            val rawJson = JSONObject(jsonString)
            val isGoogleServices = rawJson.has("client") && rawJson.has("project_info")
            if (!isGoogleServices) {
                Toast.makeText(activity, "Invalid format: Not a google-services.json file", Toast.LENGTH_LONG).show()
                return false
            }
            GoogleServicesJson.applyToPrefs(rawJson, prefs)
            if (prefs.firebaseDatabaseUrl.isBlank() && prefs.projectId.isNotBlank()) {
                prefs.firebaseDatabaseUrl = "https://${prefs.projectId}-default-rtdb.firebaseio.com"
            }
            prefs.customFirebaseApp = true
            prefs.saveToFallbackStorage(activity)

            val newSummary = "Configured: ${prefs.projectId} (Tap to update)"
            if (targetPref != null) {
                val helper = HostPreferenceHelper(activity.classLoader, activity)
                helper.setSummary(targetPref, newSummary)
            }
            Toast.makeText(
                activity,
                "Imported Firebase config for project:\n${prefs.projectId}",
                Toast.LENGTH_LONG
            ).show()
            Log.i(Consts.TAG, "Successfully applied google-services.json for project: ${prefs.projectId}")
            true
        } catch (e: Throwable) {
            Log.e(Consts.TAG, "Error importing google-services.json: ${e.message}", e)
            Toast.makeText(activity, "Failed to parse JSON: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val f = current.getDeclaredField(fieldName)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findRecyclerView(fragment: Any): RecyclerView? {
        var curr: Class<*>? = fragment.javaClass
        while (curr != null && curr != Any::class.java) {
            for (f in curr.declaredFields) {
                if (RecyclerView::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    val rv = f.get(fragment) as? RecyclerView
                    if (rv != null) return rv
                }
            }
            curr = curr.superclass
        }
        return findFieldInHierarchy(fragment.javaClass, "c")?.get(fragment) as? RecyclerView
    }

    private fun getPreferenceScreen(fragment: Any): Any? {
        return try {
            try {
                val m = fragment.javaClass.getMethod("getPreferenceScreen")
                val res = m.invoke(fragment)
                if (res != null) return res
            } catch (_: Throwable) {}

            val bField = findFieldInHierarchy(fragment.javaClass, "b")
            val bVal = bField?.get(fragment)
            if (bVal != null) {
                try {
                    val m = bVal.javaClass.getMethod("getPreferenceScreen")
                    val res = m.invoke(bVal)
                    if (res != null) return res
                } catch (_: Throwable) {}

                val gField = findFieldInHierarchy(bVal.javaClass, "g")
                val gVal = gField?.get(bVal)
                if (gVal != null) {
                    return gVal
                }
            }

            var current: Class<*>? = fragment.javaClass
            while (current != null && current != Any::class.java) {
                for (f in current.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(fragment) ?: continue
                    if (v.javaClass.name.endsWith("PreferenceScreen")) return v
                    for (subF in v.javaClass.declaredFields) {
                        subF.isAccessible = true
                        val subV = subF.get(v) ?: continue
                        if (subV.javaClass.name.endsWith("PreferenceScreen")) return subV
                    }
                }
                current = current.superclass
            }
            null
        } catch (e: Throwable) {
            Log.w(Consts.TAG, "Failed to get PreferenceScreen from fragment: ${e.message}", e)
            null
        }
    }

    private fun refreshRecyclerAdapter(fragment: Any) {
        attempt("refresh recycler adapter", silent = true) {
            val rv = findRecyclerView(fragment)
            val adapter = rv?.adapter
            if (adapter != null) {
                rv.itemAnimator = null
                adapter.notifyDataSetChanged()
                for (m in adapter.javaClass.declaredMethods) {
                    if (m.parameterCount == 0 && m.returnType == java.lang.Void.TYPE &&
                        !Modifier.isStatic(m.modifiers) && m.name != "notifyDataSetChanged"
                    ) {
                        try {
                            m.isAccessible = true
                            m.invoke(adapter)
                            Log.i(Consts.TAG, "Invoked adapter.${m.name}() successfully")
                            return@attempt
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    @Keep
    class HostPreferenceHelper(
        val cl: ClassLoader,
        val ctx: Context,
        val baseSettingsFragmentClass: Class<*>? = null
    ) {
        val prefClass: Class<*> = cl.loadClass("androidx.preference.Preference")
        val catClass: Class<*> = cl.loadClass("androidx.preference.PreferenceCategory")
        val prefGroupClass: Class<*> = cl.loadClass("androidx.preference.PreferenceGroup")

        val mSwitchClass: Class<*>? = try {
            cl.loadClass("org.swiftapps.swiftbackup.settings.MSwitchPreference")
        } catch (_: Throwable) {
            try { cl.loadClass("androidx.preference.SwitchPreferenceCompat") } catch (_: Throwable) { null }
        }

        val twoStateClass: Class<*>? = try {
            cl.loadClass("androidx.preference.TwoStatePreference")
        } catch (_: Throwable) { null }

        fun isTwoStatePreference(pref: Any): Boolean {
            return (twoStateClass != null && twoStateClass.isInstance(pref)) ||
                (mSwitchClass != null && mSwitchClass.isInstance(pref)) ||
                pref.javaClass.name.contains("Switch")
        }

        fun getKey(pref: Any): String? {
            try {
                val m = pref.javaClass.getMethod("getKey")
                return m.invoke(pref) as? String
            } catch (_: Throwable) {}
            val tField = findFieldInHierarchy(pref.javaClass, "t")
            if (tField != null) return tField.get(pref) as? String
            for (f in pref.javaClass.declaredFields) {
                if (f.type == String::class.java) {
                    f.isAccessible = true
                    val v = f.get(pref) as? String
                    if (v != null) return v
                }
            }
            return null
        }

        fun setKey(pref: Any, key: String) {
            try {
                pref.javaClass.getMethod("setKey", String::class.java).invoke(pref, key)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "t")?.set(pref, key)
        }

        fun setTitle(pref: Any, title: CharSequence) {
            try {
                pref.javaClass.getMethod("setTitle", CharSequence::class.java).invoke(pref, title)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "n")?.set(pref, title)
        }

        fun setSummary(pref: Any, summary: CharSequence) {
            try {
                pref.javaClass.getMethod("setSummary", CharSequence::class.java).invoke(pref, summary)
                return
            } catch (_: Throwable) {}
            try {
                pref.javaClass.getMethod("B", CharSequence::class.java).invoke(pref, summary)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "p")?.set(pref, summary)
        }

        fun setOrder(pref: Any, order: Int) {
            if (order == Integer.MAX_VALUE) return
            try {
                pref.javaClass.getMethod("setOrder", java.lang.Integer.TYPE).invoke(pref, order)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "k")?.set(pref, order)
        }

        fun setPersistent(pref: Any, persistent: Boolean) {
            try {
                pref.javaClass.getMethod("setPersistent", java.lang.Boolean.TYPE).invoke(pref, persistent)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "J")?.set(pref, persistent)
        }

        fun setIcon(pref: Any, iconId: Int) {
            if (iconId == 0) return
            try {
                pref.javaClass.getMethod("setIcon", java.lang.Integer.TYPE).invoke(pref, iconId)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "q")?.set(pref, iconId)
            findFieldInHierarchy(pref.javaClass, "R")?.set(pref, true)
        }

        fun setLayoutResource(pref: Any, layoutRes: Int) {
            if (layoutRes == 0) return
            try {
                pref.javaClass.getMethod("setLayoutResource", java.lang.Integer.TYPE).invoke(pref, layoutRes)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "W")?.set(pref, layoutRes)
        }

        fun setIntent(pref: Any, intent: Intent) {
            try {
                pref.javaClass.getMethod("setIntent", Intent::class.java).invoke(pref, intent)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "x")?.set(pref, intent)
        }

        fun setChecked(switchPref: Any, checked: Boolean) {
            try {
                switchPref.javaClass.getMethod("setChecked", java.lang.Boolean.TYPE).invoke(switchPref, checked)
                return
            } catch (_: Throwable) {}
            try {
                switchPref.javaClass.getMethod("G", java.lang.Boolean.TYPE).invoke(switchPref, checked)
                return
            } catch (_: Throwable) {}
            findFieldInHierarchy(switchPref.javaClass, "f0")?.set(switchPref, checked)
            findFieldInHierarchy(switchPref.javaClass, "i0")?.set(switchPref, true)
        }

        fun isChecked(switchPref: Any): Boolean {
            try {
                val m = switchPref.javaClass.getMethod("isChecked")
                return m.invoke(switchPref) as Boolean
            } catch (_: Throwable) {}
            val f0Field = findFieldInHierarchy(switchPref.javaClass, "f0")
            if (f0Field != null) {
                return f0Field.get(switchPref) as? Boolean ?: false
            }
            return false
        }

        fun setClickListener(pref: Any, listener: Any) {
            try {
                val m = pref.javaClass.declaredMethods.firstOrNull {
                    it.name == "setOnPreferenceClickListener" && it.parameterCount == 1
                }
                if (m != null) {
                    m.invoke(pref, listener)
                    return
                }
            } catch (_: Throwable) {}
            findFieldInHierarchy(pref.javaClass, "f")?.set(pref, listener)
        }

        @Suppress("UNCHECKED_CAST")
        fun getGroupList(group: Any): ArrayList<Any>? {
            var curr: Class<*>? = group.javaClass
            while (curr != null && curr != Any::class.java) {
                for (f in curr.declaredFields) {
                    if (List::class.java.isAssignableFrom(f.type)) {
                        f.isAccessible = true
                        val list = f.get(group) as? ArrayList<Any>
                        if (list != null) return list
                    }
                }
                curr = curr.superclass
            }
            val f = findFieldInHierarchy(group.javaClass, "g0")
            f?.isAccessible = true
            return f?.get(group) as? ArrayList<Any>
        }

        fun findPreference(group: Any, key: String): Any? {
            try {
                val m = group.javaClass.getMethod("findPreference", CharSequence::class.java)
                val res = m.invoke(group, key)
                if (res != null) return res
            } catch (_: Throwable) {}

            for (m in group.javaClass.methods) {
                if (m.parameterCount == 1 && m.parameterTypes[0] == CharSequence::class.java &&
                    prefClass.isAssignableFrom(m.returnType)
                ) {
                    try {
                        val res = m.invoke(group, key)
                        if (res != null) return res
                    } catch (_: Throwable) {}
                }
            }

            val list = getGroupList(group) ?: return null
            for (item in list) {
                if (getKey(item) == key) return item
                if (prefGroupClass.isInstance(item)) {
                    val sub = findPreference(item, key)
                    if (sub != null) return sub
                }
            }
            return null
        }

        fun addPreference(group: Any, pref: Any) {
            try {
                val m = group.javaClass.getMethod("addPreference", prefClass)
                m.invoke(group, pref)
                return
            } catch (_: Throwable) {}

            for (m in group.javaClass.methods) {
                if (m.parameterCount == 1 && m.parameterTypes[0] == prefClass &&
                    (m.returnType == java.lang.Boolean.TYPE || m.returnType == java.lang.Void.TYPE)
                ) {
                    try {
                        m.invoke(group, pref)
                        return
                    } catch (_: Throwable) {}
                }
            }

            val list = getGroupList(group)
            if (list != null && !list.contains(pref)) {
                for (f in pref.javaClass.declaredFields) {
                    if (prefGroupClass.isAssignableFrom(f.type)) {
                        f.isAccessible = true
                        f.set(pref, group)
                        break
                    }
                }
                findFieldInHierarchy(pref.javaClass, "a0")?.set(pref, group)
                list.add(pref)
            }
        }

        fun bindClickListeners(group: Any, listener: Any) {
            val list = getGroupList(group) ?: return
            for (item in list) {
                if (prefGroupClass.isInstance(item)) {
                    bindClickListeners(item, listener)
                } else {
                    setClickListener(item, listener)
                }
            }
        }

        fun clearScreen(screen: Any) {
            try {
                screen.javaClass.getMethod("removeAll").invoke(screen)
                return
            } catch (_: Throwable) {}
            getGroupList(screen)?.clear()
        }

        fun createCategory(title: String, order: Int = Integer.MAX_VALUE): Any {
            val cat = catClass.getConstructor(Context::class.java).newInstance(ctx)
            setTitle(cat, title)
            setPersistent(cat, false)
            setOrder(cat, order)
            return cat
        }

        fun createSwitch(
            key: String,
            title: String,
            summary: String,
            initialValue: Boolean,
            order: Int = Integer.MAX_VALUE
        ): Any {
            val switchPref = if (mSwitchClass != null && mSwitchClass.name.endsWith("MSwitchPreference")) {
                try {
                    val ctor = mSwitchClass.getConstructor(Context::class.java, AttributeSet::class.java)
                    ctor.newInstance(ctx, null)
                } catch (_: Throwable) {
                    mSwitchClass.getConstructor(Context::class.java).newInstance(ctx)
                }
            } else if (mSwitchClass != null) {
                mSwitchClass.getConstructor(Context::class.java).newInstance(ctx)
            } else {
                prefClass.getConstructor(Context::class.java).newInstance(ctx)
            }

            setKey(switchPref, key)
            setTitle(switchPref, title)
            setSummary(switchPref, summary)
            setPersistent(switchPref, false)
            setOrder(switchPref, order)
            setChecked(switchPref, initialValue)

            return switchPref
        }

        fun createItem(
            key: String,
            title: String,
            summary: String,
            iconId: Int = 0,
            order: Int = Integer.MAX_VALUE
        ): Any {
            val pref = prefClass.getConstructor(Context::class.java).newInstance(ctx)
            setKey(pref, key)
            setTitle(pref, title)
            setSummary(pref, summary)
            setPersistent(pref, false)
            setOrder(pref, order)
            if (iconId != 0) {
                setIcon(pref, iconId)
            }
            return pref
        }

        fun applySegmentedStyling(screen: Any) {
            attempt("apply baseSettingsFragment segmented styling", silent = true) {
                val styleMethod = baseSettingsFragmentClass?.declaredMethods?.firstOrNull {
                    Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 1 &&
                        prefGroupClass.isAssignableFrom(it.parameterTypes[0])
                }
                if (styleMethod != null) {
                    styleMethod.isAccessible = true
                    styleMethod.invoke(null, screen)
                }
            }
        }

        fun injectSettingsEntry(screen: Any, context: Context, fragment: Any) {
            if (findPreference(screen, PREF_KEY_SBP) != null) {
                Log.d(Consts.TAG, "SwiftBackupPrem preference already present")
                return
            }

            val res = context.resources
            val pkg = context.packageName

            val segmentedLayout = res.getIdentifier("preference_segmented", "layout", pkg)
            val boltIcon = res.getIdentifier("ic_settings_bolt_filled", "drawable", pkg)

            val sbpPref = createItem(
                PREF_KEY_SBP,
                "SwiftBackupPrem",
                "Module Settings",
                boltIcon
            )

            if (segmentedLayout != 0) {
                setLayoutResource(sbpPref, segmentedLayout)
            }

            try {
                val detailCls = cl.loadClass("org.swiftapps.swiftbackup.settings.SettingsDetailActivity")
                val intent = Intent(context, detailCls).apply {
                    putExtra("category", SBP_CATEGORY_ID)
                    putExtra("category_title", "SwiftBackupPrem")
                }
                setIntent(sbpPref, intent)
            } catch (t: Throwable) {
                Log.w(Consts.TAG, "Failed to set intent on sbpPref: ${t.message}")
            }

            setClickListener(sbpPref, fragment)

            var targetGroup: Any? = null
            val list = getGroupList(screen)
            if (list != null) {
                for (p in list) {
                    if (prefGroupClass.isInstance(p)) {
                        if (findPreference(p, "labs") != null ||
                            findPreference(p, "restart_app") != null ||
                            findPreference(p, "swiftlogger") != null
                        ) {
                            targetGroup = p
                            break
                        }
                    }
                }
            }

            if (targetGroup != null) {
                addPreference(targetGroup, sbpPref)
                Log.i(Consts.TAG, "Successfully injected SwiftBackupPrem preference into Advanced tools category!")
            } else {
                addPreference(screen, sbpPref)
                Log.i(Consts.TAG, "Successfully injected SwiftBackupPrem preference into root screen!")
            }
        }

        fun populateSbpSettings(
            screen: Any,
            activity: Activity,
            fragment: Any,
            prefs: PreferencesManager
        ) {
            val res = ctx.resources
            val pkg = ctx.packageName

            var idCounter = 100000L

            fun findPreferenceManager(target: Any): Any? {
                for (f in target.javaClass.declaredFields) {
                    if (f.type.name.contains("PreferenceManager")) {
                        f.isAccessible = true
                        return f.get(target)
                    }
                }
                return findFieldInHierarchy(target.javaClass, "b")?.get(target)
            }

            fun setPreferenceManager(pref: Any, pm: Any) {
                for (f in pref.javaClass.declaredFields) {
                    if (f.type.name.contains("PreferenceManager")) {
                        f.isAccessible = true
                        f.set(pref, pm)
                        return
                    }
                }
                findFieldInHierarchy(pref.javaClass, "b")?.set(pref, pm)
            }

            fun setPreferenceId(pref: Any, id: Long) {
                for (f in pref.javaClass.declaredFields) {
                    if (f.type == java.lang.Long.TYPE) {
                        f.isAccessible = true
                        f.set(pref, id)
                        return
                    }
                }
                findFieldInHierarchy(pref.javaClass, "c")?.set(pref, id)
            }

            val prefManager = findPreferenceManager(screen) ?: findPreferenceManager(fragment)

            fun initItem(item: Any) {
                if (prefManager != null) {
                    setPreferenceManager(item, prefManager)
                }
                setPreferenceId(item, idCounter++)
            }

            fun <T : Any> add(parent: Any, item: T): T {
                initItem(item)
                addPreference(parent, item)
                return item
            }

            val catPremium = add(screen, createCategory("Premium & System", 100))

            add(
                catPremium,
                createSwitch(
                    "enable_premium",
                    "Unlock Premium",
                    "Enables Premium Features",
                    prefs.enablePremium,
                    101
                )
            )

            add(
                catPremium,
                createSwitch(
                    "disable_telemetry",
                    "Disable Telemetry",
                    "Block analytics, crashlytics, and tracking pings",
                    prefs.disableTelemetry,
                    102
                )
            )

            add(
                catPremium,
                createSwitch(
                    "unlock_local_cloud_features",
                    "Unlock Local Cloud Providers",
                    "Enable Cloud Backups without Firebase",
                    prefs.unlockLocalCloudFeatures,
                    103
                )
            )

            val catCloud = add(screen, createCategory("Cloud Integrations", 200))

            add(
                catCloud,
                createSwitch(
                    "custom_firebase_app",
                    "Custom Firebase App",
                    "Use self-hosted Firebase project for cloud features",
                    prefs.customFirebaseApp,
                    201
                )
            )

            val uploadIcon = res.getIdentifier("ic_settings_upload_file_filled", "drawable", pkg)
            val pId = prefs.projectId
            val importSummary = if (pId.isNotBlank()) {
                "Configured: $pId (Tap to update)"
            } else {
                "Import credentials from google-services.json"
            }

            val importItem = createItem(
                PREF_KEY_IMPORT_JSON,
                "Import google-services.json",
                importSummary,
                uploadIcon,
                202
            )
            add(catCloud, importItem)

            val catLinks = add(screen, createCategory("Links", 300))

            val telegramIcon = res.getIdentifier("ic_telegram", "drawable", pkg)
            val linkIcon = res.getIdentifier("ic_link_outline", "drawable", pkg)

            val telegramItem = createItem(
                "pref_link_telegram",
                "Telegram Group",
                "Join chat, release updates, and support",
                telegramIcon,
                301
            )
            val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/SwiftBackupPrem")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            setIntent(telegramItem, telegramIntent)
            add(catLinks, telegramItem)

            val githubItem = createItem(
                "pref_link_github",
                "GitHub",
                "Source code, releases, and issue tracker",
                linkIcon,
                302
            )
            val githubIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/s1ddhants1/SwiftBackupPrem")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            setIntent(githubItem, githubIntent)
            add(catLinks, githubItem)

            bindClickListeners(screen, fragment)

            applySegmentedStyling(screen)

            Log.i(Consts.TAG, "Rendered native SwiftBackupPrem settings screen in SettingsDetailActivity")
        }
    }
}

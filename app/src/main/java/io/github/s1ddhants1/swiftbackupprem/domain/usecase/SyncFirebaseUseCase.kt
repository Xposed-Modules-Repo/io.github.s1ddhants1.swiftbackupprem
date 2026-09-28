package io.github.s1ddhants1.swiftbackupprem.domain.usecase

import android.content.Context
import io.github.s1ddhants1.swiftbackupprem.util.FirebaseSyncEngine
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

class SyncFirebaseUseCase {
    operator fun invoke(context: Context, prefs: PreferencesManager, force: Boolean = true): FirebaseSyncEngine.SyncResult {
        return FirebaseSyncEngine.syncAll(context.applicationContext, prefs, force = force)
    }
}

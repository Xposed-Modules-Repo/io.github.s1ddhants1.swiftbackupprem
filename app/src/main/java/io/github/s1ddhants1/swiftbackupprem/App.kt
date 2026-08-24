package io.github.s1ddhants1.swiftbackupprem

import android.app.Application
import androidx.annotation.Keep

class App : Application() {
    companion object {
        @Keep
        @JvmStatic
        fun isModuleActive(): Boolean = false
    }
}

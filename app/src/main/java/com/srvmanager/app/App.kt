package com.srvmanager.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.srvmanager.app.data.SecureStore
import com.srvmanager.app.data.ServerRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** Простейший контейнер зависимостей приложения. */
object Graph {
    lateinit var app: Application
        private set
    lateinit var repo: ServerRepository
        private set

    /** Область для операций, которые не должны прерываться при уходе с экрана. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("Graph", "Фоновая операция завершилась ошибкой", e) },
    )

    fun init(app: Application) {
        this.app = app
        repo = ServerRepository(SecureStore(app), appScope)
    }
}

/** Несекретные настройки интерфейса. */
object Prefs {
    private val sp: SharedPreferences by lazy {
        Graph.app.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
    }

    var terminalFontSp: Float
        get() = sp.getFloat("terminal_font_sp", 13f)
        set(value) = sp.edit().putFloat("terminal_font_sp", value).apply()

    var monitorIntervalSec: Int
        get() = sp.getInt("monitor_interval_sec", 3)
        set(value) = sp.edit().putInt("monitor_interval_sec", value).apply()
}

package com.superdownloader.app

import android.app.Application
import com.superdownloader.app.data.AppDatabase
import com.superdownloader.app.data.AppSettings
import com.superdownloader.app.engine.DownloadEngine
import com.superdownloader.app.service.Notifier
import com.superdownloader.app.service.RestoreWorker

class SuperApp : Application() {
    lateinit var db: AppDatabase
    lateinit var settings: AppSettings
    lateinit var notifier: Notifier
    lateinit var engine: DownloadEngine

    override fun onCreate() {
        super.onCreate()
        Credits.touch()
        db = AppDatabase.build(this)
        settings = AppSettings(this)
        notifier = Notifier(this)
        engine = DownloadEngine(this, db, settings, notifier)
        engine.start()
        Thread { runCatching { engine.output.ensureFolders() } }.start()
        RestoreWorker.enqueue(this, settings.value.wifiOnly)
    }
}

package com.saara.assistant

import android.app.Application
import com.google.firebase.FirebaseApp

class SaaraApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // Initialize Firebase (requires google-services.json in app/ for full functionality).
    try { FirebaseApp.initializeApp(this) } catch (_: Exception) {}
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      try { Crash.save(this, error, "Uncaught exception in " + thread.name) } catch (_: Throwable) {}
      previous?.uncaughtException(thread, error)
    }
  }
}

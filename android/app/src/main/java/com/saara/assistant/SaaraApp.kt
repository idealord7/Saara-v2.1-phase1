package com.saara.assistant

import android.app.Application

class SaaraApp : Application() {
  override fun onCreate() {
    super.onCreate()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      try { Crash.save(this, error, "Uncaught exception in " + thread.name) } catch (_: Throwable) {}
      previous?.uncaughtException(thread, error)
    }
  }
}

package com.saara.assistant
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Timed tasks ("every day at 8 remind me ...", "in 10 minutes call ..."). Stored in SQLite, fired by AlarmManager. */
object Scheduler {
  private fun pi(c: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(c, id.toInt(),
    Intent(c, TaskReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  fun schedule(c: Context, id: Long, whenMs: Long) {
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi(c, id))
  }
  fun cancel(c: Context, id: Long) {
    (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c, id))
    Db.get(c).taskDelete(id)
  }
  fun rescheduleAll(c: Context) {
    val now = System.currentTimeMillis()
    Db.get(c).tasks().forEach { schedule(c, it.id, if (it.whenMs < now) now + 5000 else it.whenMs) }
  }

  private fun fmt(ms: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

  /** in_minutes OR at ("HH:mm" or "yyyy-MM-dd HH:mm"); repeat = none | hourly | daily | weekly. */
  fun add(c: Context, text: String, inMinutes: Double, at: String, repeat: String): String {
    if (text.isBlank()) return "error: text is required"
    val now = System.currentTimeMillis()
    var whenMs = 0L
    if (inMinutes > 0) whenMs = now + (inMinutes * 60000).toLong()
    else if (at.isNotBlank()) {
      val a = at.trim()
      try {
        if (Regex("^\\d{1,2}:\\d{2}$").matches(a)) {
          val cal = Calendar.getInstance()
          cal.set(Calendar.HOUR_OF_DAY, a.substringBefore(':').toInt()); cal.set(Calendar.MINUTE, a.substringAfter(':').toInt())
          cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
          if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
          whenMs = cal.timeInMillis
        } else whenMs = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(a)?.time ?: 0L
      } catch (e: Exception) { return "error: could not read time '$at'. Use HH:mm or yyyy-MM-dd HH:mm, or in_minutes." }
    }
    if (whenMs <= now) return "error: give in_minutes (> 0) or a future time in 'at'."
    val rep = when (repeat.lowercase()) { "hourly" -> 3600000L; "daily" -> 86400000L; "weekly" -> 604800000L; else -> 0L }
    val id = Db.get(c).taskAdd(whenMs, text.trim(), rep)
    schedule(c, id, whenMs)
    return "scheduled #$id for ${fmt(whenMs)}" + (if (rep > 0) ", repeats $repeat" else "")
  }

  fun list(c: Context): String {
    val t = Db.get(c).tasks()
    return if (t.isEmpty()) "no scheduled tasks" else t.joinToString("; ") { "#${it.id} ${fmt(it.whenMs)}${if (it.repeatMs > 0) " (repeats)" else ""}: ${it.text}" }
  }

  fun notify(c: Context, text: String) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("saara_tasks", "Saara reminders", NotificationManager.IMPORTANCE_DEFAULT))
    val open = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    nm.notify((System.currentTimeMillis() % 100000).toInt() + 10, android.app.Notification.Builder(c, "saara_tasks")
      .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Saara").setContentText(text)
      .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
  }
}

class TaskReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) {
    val id = i.getLongExtra("id", -1L)
    val db = Db.get(c)
    val t = db.task(id) ?: return
    val delivered = VoiceService.instance?.runScheduled(t.text) == true
    if (!delivered) Scheduler.notify(c, t.text)
    if (t.repeatMs > 0) {
      var n = t.whenMs + t.repeatMs
      while (n <= System.currentTimeMillis()) n += t.repeatMs
      db.taskUpdateWhen(id, n); Scheduler.schedule(c, id, n)
    } else db.taskDelete(id)
  }
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.rescheduleAll(c) }
}

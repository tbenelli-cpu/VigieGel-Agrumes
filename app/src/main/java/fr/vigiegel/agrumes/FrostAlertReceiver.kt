package fr.vigiegel.agrumes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.Notification
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class FrostAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val notifEnabled = prefs.getBoolean("notif_enabled", false)
        val notifHour = prefs.getInt("notif_hour", 18)
        val notifMin = prefs.getInt("notif_min", 0)

        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (notifEnabled) scheduleDailyCheck(context, true, notifHour, notifMin)
            return
        }

        val isTest = intent?.getBooleanExtra("is_test", false) ?: false
        if (!notifEnabled && !isTest) return

        val pendingResult = goAsync()
        Executors.newSingleThreadExecutor().execute {
            try {
                val lat = prefs.getFloat("lat", 48.6644f).toDouble()
                val lon = prefs.getFloat("lon", 2.3514f).toDouble()
                val city = prefs.getString("city", "Mon jardin") ?: "Mon jardin"
                val offset = ((prefs.getFloat("offset", 0.0f).toDouble() * 10.0).roundToInt() / 10.0)

                val apiUrl = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/meteofrance?latitude=%.4f&longitude=%.4f" +
                            "&hourly=temperature_2m,wind_speed_10m,cloud_cover&timezone=Europe%%2FParis&forecast_days=2",
                    lat, lon
                )
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val hourly = json.getJSONObject("hourly")
                val times = hourly.getJSONArray("time")
                val temps = hourly.getJSONArray("temperature_2m")
                val winds = hourly.getJSONArray("wind_speed_10m")
                val clouds = hourly.getJSONArray("cloud_cover")

                val cal = Calendar.getInstance()
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val todayStr = sdfDate.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val tomorrowStr = sdfDate.format(cal.time)

                var minAbri = Double.MAX_VALUE
                var minPot = Double.MAX_VALUE
                var minHour = "--h--"

                for (i in 0 until times.length()) {
                    val tStr = times.getString(i)
                    val hourNum = tStr.substringAfter("T").substringBefore(":").toIntOrNull() ?: 0
                    val isTonight = (tStr.startsWith(todayStr) && hourNum >= 18) ||
                            (tStr.startsWith(tomorrowStr) && hourNum <= 10)

                    if (isTonight && !temps.isNull(i)) {
                        val t2m = temps.getDouble(i) + offset
                        val wind = if (!winds.isNull(i)) winds.getDouble(i) else 10.0
                        val cloud = if (!clouds.isNull(i)) clouds.getDouble(i) else 50.0
                        val clearSky = (100.0 - cloud) / 100.0
                        val calmWind = if (wind < 8.0) (8.0 - wind) / 8.0 else 0.2
                        val potTemp = t2m - (0.6 + 1.6 * clearSky * calmWind)

                        if (t2m < minAbri) {
                            minAbri = t2m
                            minHour = tStr.substringAfter("T")
                        }
                        if (potTemp < minPot) minPot = potTemp
                    }
                }

                // Déclenche la notification si risque de gel sur les pots (<= +2.0°C) ou si bouton Test pressé
                if (minPot <= 2.0 || isTest) {
                    sendNotification(context, city, minAbri, minPot, minHour, offset, isTest)
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "vigiegel_alert_channel"

        fun scheduleDailyCheck(context: Context, enabled: Boolean, hourOfDay: Int = 18, minute: Int = 0) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, FrostAlertReceiver::class.java).apply {
                action = "fr.vigiegel.agrumes.ACTION_CHECK_FROST"
            }
            val pi = PendingIntent.getBroadcast(
                context, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!enabled) {
                am.cancel(pi)
                return
            }
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pi
            )
        }

        fun sendNotification(
            context: Context,
            city: String,
            minAbri: Double,
            minPot: Double,
            minHour: String,
            offset: Double,
            isTest: Boolean
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Alertes Gel Agrumes",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notification quotidienne à 19h en cas de risque de gel nocturne"
                }
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, MainActivity::class.java)
            val contentPi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = when {
                minPot <= -2.5 -> "🚨 ALERTE GEL SÉVÈRE ($city)"
                minPot <= 0.0 -> "🔶 ALERTE GEL SUR LES POTS ($city)"
                minPot <= 2.0 -> "⚠️ Vigilance Gelée Blanche ($city)"
                else -> "✅ Test VigieGel ($city) : Nuit sûre"
            }

            val offsetTxt = if (offset != 0.0) String.format(Locale.FRANCE, " (étalonné %+.1f°C)", offset) else ""
            val body = String.format(
                Locale.FRANCE,
                "Min demain : %.1f°C vers %s | Pots : %.1f°C%s",
                minAbri, minHour, minPot, offsetTxt
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(contentPi)
                .setAutoCancel(true)

            nm.notify(if (isTest) 3002 else 3001, builder.build())
        }
    }
}

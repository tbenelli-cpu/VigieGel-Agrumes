package fr.vigiegel.agrumes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var cityText: TextView
    private lateinit var searchInput: EditText
    private lateinit var tempMinText: TextView
    private lateinit var tempMinHourText: TextView
    private lateinit var radiativeMinText: TextView
    private lateinit var windMaxText: TextView
    private lateinit var hoursBelowZeroText: TextView
    private lateinit var alertBanner: TextView
    private lateinit var alertAdviceText: TextView
    private lateinit var hourlyContainer: LinearLayout

    private var currentLat = 48.6644
    private var currentLon = 2.3514
    private var currentCityName = "Localisation en cours..."

    companion object {
        private const val LOCATION_PERMISSION_REQ = 1001
        private const val PREFS_NAME = "VigieGelLocationPrefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains("lat") && prefs.contains("lon")) {
            currentLat = prefs.getFloat("lat", 48.6644f).toDouble()
            currentLon = prefs.getFloat("lon", 2.3514f).toDouble()
            currentCityName = prefs.getString("city", "Position enregistrée") ?: "Position enregistrée"
            fetchMeteoFranceData(currentLat, currentLon, currentCityName)
        } else {
            requestGpsLocation()
        }
    }

    private fun saveLastLocation(lat: Double, lon: Double, city: String) {
        currentLat = lat
        currentLon = lon
        currentCityName = city
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("lat", lat.toFloat())
            .putFloat("lon", lon.toFloat())
            .putString("city", city)
            .apply()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun makeCard(bgColor: String, strokeColor: String = "#334155"): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor(strokeColor))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
    }

    private fun buildUI() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "❄️🍊 VigieGel Agrumes"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        cityText = TextView(this).apply {
            text = "📍 $currentCityName"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(2))
        }
        statusText = TextView(this).apply {
            text = "Modèles Météo-France AROME (1.3 km) & ARPEGE"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(title)
        root.addView(cityText)
        root.addView(statusText)

        val btnGps = Button(this).apply {
            text = "📡 Actualiser ma position GPS"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { requestGpsLocation() }
        }
        root.addView(btnGps)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        searchInput = EditText(this).apply {
            hint = "Ou chercher une ville (ex: Brunoy, 91800...)"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                rightMargin = dp(8)
            }
        }
        val btnSearch = Button(this).apply {
            text = "🔍 OK"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(42))
            setOnClickListener { searchCityByName() }
        }
        searchRow.addView(searchInput)
        searchRow.addView(btnSearch)
        root.addView(searchRow)

        val alertCard = makeCard("#1E293B", "#475569")
        alertBanner = TextView(this).apply {
            text = "Analyse thermique en cours..."
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        alertAdviceText = TextView(this).apply {
            text = "Chargement des prévisions nocturnes Météo-France..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        alertCard.addView(alertBanner)
        alertCard.addView(alertAdviceText)
        root.addView(alertCard)

        val mainCard = makeCard("#1E293B")
        val labelMin = TextView(this).apply {
            text = "TEMPÉRATURE MINIMALE DEMAIN MATIN (SOUS ABRI 2M)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        tempMinText = TextView(this).apply {
            text = "--.- °C"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(2))
        }
        tempMinHourText = TextView(this).apply {
            text = "Pic de froid prévu vers --h--"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }
        radiativeMinText = TextView(this).apply {
            text = "🪴 Indice au niveau des pots (gel radiatif) : --.- °C"
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }
        hoursBelowZeroText = TextView(this).apply {
            text = "⏱️ Durée sous 0°C (18h → 10h) : 0 h"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
        }
        windMaxText = TextView(this).apply {
            text = "💨 Rafales max la nuit : -- km/h"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
        }
        mainCard.addView(labelMin)
        mainCard.addView(tempMinText)
        mainCard.addView(tempMinHourText)
        mainCard.addView(radiativeMinText)
        mainCard.addView(hoursBelowZeroText)
        mainCard.addView(windMaxText)
        root.addView(mainCard)

        val hourlyCard = makeCard("#1E293B")
        val hourlyTitle = TextView(this).apply {
            text = "🌙 Évolution horaire nocturne (18h00 ➔ 10h00)"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        hourlyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        hourlyCard.addView(hourlyTitle)
        hourlyCard.addView(hourlyContainer)
        root.addView(hourlyCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {}
    }

    private fun searchCityByName() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Saisissez le nom d\u0027une commune ou un code postal", Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        statusText.text = "Recherche de la commune \"$query\"..."
        executor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val isPostal = query.all { it.isDigit() } && query.length == 5
                val apiUrl = if (isPostal) {
                    "https://geo.api.gouv.fr/communes?codePostal=$encoded&fields=nom,codeDepartement,centre&limit=1"
                } else {
                    "https://geo.api.gouv.fr/communes?nom=$encoded&fields=nom,codeDepartement,centre&boost=population&limit=1"
                }
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val nom = obj.getString("nom")
                    val dep = obj.optString("codeDepartement", "")
                    val coords = obj.getJSONObject("centre").getJSONArray("coordinates")
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val label = if (dep.isNotEmpty()) "$nom ($dep)" else nom
                    runOnUiThread {
                        searchInput.setText("")
                        saveLastLocation(lat, lon, label)
                        fetchMeteoFranceData(lat, lon, label)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Commune introuvable : $query", Toast.LENGTH_LONG).show()
                        statusText.text = "Commune non trouvée."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Erreur lors de la recherche de commune."
                }
            }
        }
    }

    private fun requestGpsLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    LOCATION_PERMISSION_REQ
                )
                return
            }
        }
        startFreshLocationLookup()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQ && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startFreshLocationLookup()
        } else {
            Toast.makeText(
                this,
                "GPS refusé : utilisez la barre de recherche pour choisir votre commune.",
                Toast.LENGTH_LONG
            ).show()
            fetchMeteoFranceData(currentLat, currentLon, currentCityName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFreshLocationLookup() {
        cityText.text = "📍 Acquisition GPS en cours..."
        statusText.text = "Interrogation de la puce GPS du téléphone..."
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !netEnabled) {
            Toast.makeText(this, "Activez la localisation GPS sur le téléphone", Toast.LENGTH_LONG).show()
            statusText.text = "GPS désactivé sur le téléphone."
            return
        }

        var resolved = false
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (!resolved) {
                    resolved = true
                    lm.removeUpdates(this)
                    onLocationFound(loc.latitude, loc.longitude)
                }
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        if (netEnabled) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, mainLooper)
        if (gpsEnabled) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, mainLooper)

        mainHandler.postDelayed({
            if (!resolved) {
                resolved = true
                lm.removeUpdates(listener)
                var fallbackLoc: Location? = null
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (lm.isProviderEnabled(p)) {
                        val l = lm.getLastKnownLocation(p)
                        if (l != null && (fallbackLoc == null || l.time > fallbackLoc.time)) {
                            fallbackLoc = l
                        }
                    }
                }
                if (fallbackLoc != null) {
                    onLocationFound(fallbackLoc.latitude, fallbackLoc.longitude)
                } else {
                    statusText.text = "Signal GPS faible : tapez votre ville dans la barre ci-dessus."
                }
            }
        }, 6000L)
    }

    private fun onLocationFound(lat: Double, lon: Double) {
        executor.execute {
            val commune = reverseGeocodeCommune(lat, lon)
            runOnUiThread {
                saveLastLocation(lat, lon, commune)
                fetchMeteoFranceData(lat, lon, commune)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeCommune(lat: Double, lon: Double): String {
        try {
            val urlStr = String.format(
                Locale.US,
                "https://geo.api.gouv.fr/communes?lat=%.5f&lon=%.5f&fields=nom,codeDepartement",
                lat, lon
            )
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val obj = arr.getJSONObject(0)
                val nom = obj.getString("nom")
                val dep = obj.optString("codeDepartement", "")
                return if (dep.isNotEmpty()) "$nom ($dep)" else nom
            }
        } catch (_: Exception) {}

        try {
            val geocoder = Geocoder(this, Locale.FRANCE)
            val list = geocoder.getFromLocation(lat, lon, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                val postal = addr.postalCode?.take(2) ?: ""
                if (!city.isNullOrEmpty()) {
                    return if (postal.isNotEmpty()) "$city ($postal)" else city
                }
            }
        } catch (_: Exception) {}

        return String.format(Locale.US, "Position GPS (%.3f, %.3f)", lat, lon)
    }

    private fun fetchMeteoFranceData(lat: Double, lon: Double, label: String) {
        cityText.text = "📍 $label"
        statusText.text = "Interrogation du modèle AROME / ARPEGE Météo-France..."

        executor.execute {
            try {
                val apiUrl = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/meteofrance?latitude=%.4f&longitude=%.4f" +
                            "&hourly=temperature_2m,dew_point_2m,wind_speed_10m,wind_gusts_10m,cloud_cover" +
                            "&daily=temperature_2m_min,temperature_2m_max&timezone=Europe%%2FParis&forecast_days=3",
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
                val gusts = hourly.getJSONArray("wind_gusts_10m")
                val clouds = hourly.getJSONArray("cloud_cover")

                val cal = Calendar.getInstance()
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val todayStr = sdfDate.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val tomorrowStr = sdfDate.format(cal.time)

                var minTempTomorrow = Double.MAX_VALUE
                var minTempHour = "--h--"
                var minRadiativePot = Double.MAX_VALUE
                var maxNightGust = 0.0
                var hoursUnderZero = 0

                data class HourPoint(
                    val hourLabel: String,
                    val temp2m: Double,
                    val potTemp: Double
                )
                val nightPoints = mutableListOf<HourPoint>()

                for (i in 0 until times.length()) {
                    val tStr = times.getString(i)
                    if (tStr.startsWith(tomorrowStr) && !temps.isNull(i)) {
                        val tVal = temps.getDouble(i)
                        if (tVal < minTempTomorrow) {
                            minTempTomorrow = tVal
                            minTempHour = tStr.substringAfter("T")
                        }
                    }

                    val hourNum = tStr.substringAfter("T").substringBefore(":").toIntOrNull() ?: 0
                    val isTonightWindow =
                        (tStr.startsWith(todayStr) && hourNum >= 18) ||
                                (tStr.startsWith(tomorrowStr) && hourNum <= 10)

                    if (isTonightWindow && !temps.isNull(i)) {
                        val t2m = temps.getDouble(i)
                        val wind = if (!winds.isNull(i)) winds.getDouble(i) else 10.0
                        val gust = if (!gusts.isNull(i)) gusts.getDouble(i) else wind
                        val cloud = if (!clouds.isNull(i)) clouds.getDouble(i) else 50.0

                        val clearSkyFactor = (100.0 - cloud) / 100.0
                        val calmWindFactor = if (wind < 8.0) (8.0 - wind) / 8.0 else 0.2
                        val radiativeDrop = 0.6 + (1.6 * clearSkyFactor * calmWindFactor)
                        val potTemp = t2m - radiativeDrop

                        if (potTemp < minRadiativePot) minRadiativePot = potTemp
                        if (gust > maxNightGust) maxNightGust = gust
                        if (t2m <= 0.0) hoursUnderZero++

                        val dayPrefix = if (tStr.startsWith(todayStr)) "Ce soir" else "Demain"
                        nightPoints.add(
                            HourPoint(
                                "$dayPrefix ${tStr.substringAfter("T")}",
                                t2m,
                                potTemp
                            )
                        )
                    }
                }

                runOnUiThread {
                    val timeNow = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
                    statusText.text = "Météo-France AROME ($label) • màj $timeNow"

                    tempMinText.text = String.format(Locale.FRANCE, "%.1f °C", minTempTomorrow)
                    tempMinHourText.text = "Pic minimal attendu demain vers $minTempHour"
                    radiativeMinText.text = String.format(
                        Locale.FRANCE,
                        "🪴 Température estimée sur les pots (radiatif) : %.1f °C",
                        minRadiativePot
                    )
                    hoursBelowZeroText.text = "⏱️ Durée sous 0°C cette nuit : $hoursUnderZero h"
                    windMaxText.text = String.format(
                        Locale.FRANCE,
                        "💨 Rafales max nocturnes : %d km/h",
                        maxNightGust.roundToInt()
                    )

                    when {
                        minRadiativePot > 3.0 -> {
                            alertBanner.text = "✅ AUCUN RISQUE DE GEL (NUIT SÛRE)"
                            alertBanner.setTextColor(Color.parseColor("#4ADE80"))
                            alertAdviceText.text =
                                "Tous les agrumes en pot peuvent rester découverts sans protection."
                        }
                        minRadiativePot > 0.0 -> {
                            alertBanner.text = "⚠️ VIGILANCE GEL BLANC / RADIATIF (0°C à +3°C)"
                            alertBanner.setTextColor(Color.parseColor("#FACC15"))
                            alertAdviceText.text =
                                "Risque de gelée blanche sur le feuillage exposé et les fruits sensibles. Rapprochez les pots de 20L-50L d\u0027un mur abrité."
                        }
                        minRadiativePot > -2.5 -> {
                            alertBanner.text = "🔶 ALERTE GEL MODÉRÉ (0°C à -2.5°C SUR POTS)"
                            alertBanner.setTextColor(Color.parseColor("#FB923C"))
                            alertAdviceText.text =
                                "Posez un voile d\u0027hivernage P30 sur les agrumes porteurs de fruits et protégez les conteneurs de 20L à 50L."
                        }
                        else -> {
                            alertBanner.text = "🚨 ALERTE GEL SÉVÈRE (< -2.5°C)"
                            alertBanner.setTextColor(Color.parseColor("#F87171"))
                            alertAdviceText.text =
                                "Gel destructeur pour les fruits et les racines en conteneurs ! Double voile P30/P60 obligatoire et rentrez les sujets les plus fragiles."
                        }
                    }

                    hourlyContainer.removeAllViews()
                    for (pt in nightPoints) {
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(6), 0, dp(6))
                        }
                        val tvHour = TextView(this@MainActivity).apply {
                            text = pt.hourLabel
                            setTextColor(Color.parseColor("#CBD5E1"))
                            textSize = 13f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
                        }
                        val tvTemp = TextView(this@MainActivity).apply {
                            text = String.format(Locale.FRANCE, "Abri: %.1f°C", pt.temp2m)
                            setTextColor(
                                when {
                                    pt.temp2m <= 0.0 -> Color.parseColor("#F87171")
                                    pt.temp2m <= 2.5 -> Color.parseColor("#FACC15")
                                    else -> Color.parseColor("#4ADE80")
                                }
                            )
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val tvPot = TextView(this@MainActivity).apply {
                            text = String.format(Locale.FRANCE, "Pots: %.1f°C", pt.potTemp)
                            setTextColor(Color.parseColor("#38BDF8"))
                            textSize = 12f
                            gravity = Gravity.END
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        row.addView(tvHour)
                        row.addView(tvTemp)
                        row.addView(tvPot)
                        hourlyContainer.addView(row)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Erreur réseau : vérifiez votre connexion Internet (${e.localizedMessage})"
                }
            }
        }
    }
}

package fr.vigiegel.agrumes

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.view.View
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

    private lateinit var titleText: TextView
    private lateinit var btnSettingsView: Button
    private lateinit var btnGpsView: Button
    private lateinit var btnSearchView: Button
    private lateinit var hourlyTitleView: TextView
    private lateinit var statusText: TextView
    private lateinit var cityText: TextView
    private lateinit var btnFavHeart: TextView
    private lateinit var favoritesContainer: LinearLayout
    private lateinit var badgeCalibrationText: TextView
    private lateinit var searchInput: EditText
    private lateinit var labelMinTitle: TextView
    private lateinit var tempMinText: TextView
    private lateinit var tempMinHourText: TextView
    private lateinit var radiativeMinText: TextView
    private lateinit var windMaxText: TextView
    private lateinit var hoursBelowZeroText: TextView
    private lateinit var alertBanner: TextView
    private lateinit var alertAdviceText: TextView
    private lateinit var hourlyContainer: LinearLayout
    private lateinit var chartTitleView: TextView
    private lateinit var chartLegendView: TextView
    private lateinit var barChartView: FrostBarChartView
    private lateinit var forecastDaysTitleView: TextView
    private lateinit var forecastDaysSubView: TextView
    private lateinit var forecastDaysContainer: LinearLayout

    private var currentLat = 48.6644
    private var currentLon = 2.3514
    private var currentCityName = ""
    private var tempOffset = 0.0
    private var notifEnabled = false
    private var notifHour = 18
    private var notifMin = 0
    private var currentLang = "fr"

    data class FavoritePlace(val label: String, val lat: Double, val lon: Double, var offset: Double)
    private val favoritePlaces = mutableListOf<FavoritePlace>()

    data class RawHourPoint(
        val isToday: Boolean,
        val timeStr: String,
        val rawTemp2m: Double,
        val rawPotTemp: Double,
        val gust: Double
    )
    private var rawCurrentTemp: Double? = null
    private var rawCurrentTime: String = "--:--"
    private var rawMinTempTomorrow: Double? = null
    private var rawMinTempHour: String = "--h--"
    data class RawDayPoint(val dateStr: String, val rawMin2m: Double, val rawMinPot: Double)
    private var rawNightPoints = listOf<RawHourPoint>()
    private var rawMultiDays = listOf<RawDayPoint>()

    companion object {
        private const val LOCATION_PERMISSION_REQ = 1001
        private const val NOTIF_PERMISSION_REQ = 1002
        const val PREFS_NAME = "VigieGelLocationPrefs"
    }

    private fun tr(key: String, vararg args: Any): String = StringsProvider.t(currentLang, key, *args)
    private fun loc(): Locale = StringsProvider.getLocale(currentLang)
    private fun fmtTime(h: Int, m: Int): String =
        if (currentLang == "fr") String.format(Locale.FRANCE, "%02dh%02d", h, m)
        else String.format(Locale.US, "%02d:%02d", h, m)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tempOffset = ((prefs.getFloat("offset", 0.0f).toDouble() * 10.0).roundToInt() / 10.0)
        notifEnabled = prefs.getBoolean("notif_enabled", false)
        notifHour = prefs.getInt("notif_hour", 18)
        notifMin = prefs.getInt("notif_min", 0)
        currentLang = prefs.getString("lang", "fr") ?: "fr"
        currentCityName = tr("locating")
        loadFavorites()
        FrostAlertReceiver.scheduleDailyCheck(this, notifEnabled, notifHour, notifMin)

        buildUI()

        if (prefs.contains("lat") && prefs.contains("lon")) {
            currentLat = prefs.getFloat("lat", 48.6644f).toDouble()
            currentLon = prefs.getFloat("lon", 2.3514f).toDouble()
            currentCityName = prefs.getString("city", tr("saved_pos")) ?: tr("saved_pos")
            fetchMeteoFranceData(currentLat, currentLon, currentCityName)
        } else {
            requestGpsLocation()
        }
    }

    private fun loadFavorites() {
        favoritePlaces.clear()
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("favorites_json", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                favoritePlaces.add(
                    FavoritePlace(
                        obj.getString("label"),
                        obj.getDouble("lat"),
                        obj.getDouble("lon"),
                        ((obj.optDouble("offset", 0.0) * 10.0).roundToInt() / 10.0)
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun saveFavoritesToPrefs() {
        val arr = JSONArray()
        for (fav in favoritePlaces) {
            arr.put(JSONObject().apply {
                put("label", fav.label)
                put("lat", fav.lat)
                put("lon", fav.lon)
                put("offset", fav.offset)
            })
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("favorites_json", arr.toString())
            .apply()
    }

    private fun findMatchingFavorite(label: String, lat: Double, lon: Double): FavoritePlace? {
        return favoritePlaces.find {
            it.label.equals(label, ignoreCase = true) ||
                    (kotlin.math.abs(it.lat - lat) < 0.02 && kotlin.math.abs(it.lon - lon) < 0.02)
        }
    }

    private fun toggleCurrentFavorite() {
        if (currentCityName.isEmpty() || currentCityName == tr("locating") || currentCityName == tr("saved_pos")) return
        val existing = findMatchingFavorite(currentCityName, currentLat, currentLon)
        if (existing != null) {
            favoritePlaces.remove(existing)
            saveFavoritesToPrefs()
            updateFavoritesUI()
            Toast.makeText(this, tr("fav_removed", existing.label), Toast.LENGTH_SHORT).show()
        } else {
            if (favoritePlaces.size >= 4) {
                Toast.makeText(this, tr("fav_full"), Toast.LENGTH_LONG).show()
                return
            }
            favoritePlaces.add(FavoritePlace(currentCityName, currentLat, currentLon, tempOffset))
            saveFavoritesToPrefs()
            updateFavoritesUI()
            Toast.makeText(this, tr("fav_added", currentCityName), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFavoritesUI() {
        if (!::btnFavHeart.isInitialized || !::favoritesContainer.isInitialized) return
        val isFav = findMatchingFavorite(currentCityName, currentLat, currentLon) != null
        btnFavHeart.text = if (isFav) "❤️" else "🤍"

        favoritesContainer.removeAllViews()
        if (favoritePlaces.isEmpty()) {
            favoritesContainer.visibility = View.GONE
            return
        }
        favoritesContainer.visibility = View.VISIBLE

        val rows = favoritePlaces.chunked(2)
        for (rowIdx in rows.indices) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx < rows.lastIndex) bottomMargin = dp(6)
                }
            }

            val pair = rows[rowIdx]
            for (colIdx in pair.indices) {
                val fav = pair[colIdx]
                val isCurrent = findMatchingFavorite(currentCityName, currentLat, currentLon) == fav
                val shortName = fav.label.substringBefore("(").trim()
                val chipText = if (fav.offset != 0.0) {
                    String.format(loc(), "❤️ %s (%+.1f°)", shortName, fav.offset)
                } else {
                    "❤️ $shortName"
                }

                val chip = TextView(this).apply {
                    text = chipText
                    setTextColor(if (isCurrent) Color.WHITE else Color.parseColor("#CBD5E1"))
                    textSize = 12f
                    typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor(if (isCurrent) "#0284C7" else "#1E293B"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor(if (isCurrent) "#38BDF8" else "#475569"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (colIdx == 0 && pair.size > 1) rightMargin = dp(6)
                    }
                    setOnClickListener {
                        tempOffset = fav.offset
                        saveLastLocation(fav.lat, fav.lon, fav.label)
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putFloat("offset", tempOffset.toFloat())
                            .apply()
                        fetchMeteoFranceData(fav.lat, fav.lon, fav.label)
                    }
                }
                rowLayout.addView(chip)
            }
            favoritesContainer.addView(rowLayout)
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

    private fun saveSettings(newOffset: Double, newNotif: Boolean, newHour: Int, newMin: Int, newLang: String) {
        val langChanged = (newLang != currentLang)
        tempOffset = ((newOffset * 10.0).roundToInt() / 10.0)
        notifEnabled = newNotif
        notifHour = newHour
        notifMin = newMin
        currentLang = newLang
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("offset", tempOffset.toFloat())
            .putBoolean("notif_enabled", notifEnabled)
            .putInt("notif_hour", notifHour)
            .putInt("notif_min", notifMin)
            .putString("lang", currentLang)
            .apply()
        findMatchingFavorite(currentCityName, currentLat, currentLon)?.let {
            it.offset = tempOffset
            saveFavoritesToPrefs()
        }
        updateFavoritesUI()
        FrostAlertReceiver.scheduleDailyCheck(this, notifEnabled, notifHour, notifMin)
        if (langChanged) applyStaticLabels()
        refreshDisplayedData()
    }

    private fun applyStaticLabels() {
        titleText.text = tr("app_title")
        btnSettingsView.text = tr("btn_settings")
        btnGpsView.text = tr("btn_gps")
        searchInput.hint = tr("search_hint")
        btnSearchView.text = tr("btn_search")
        hourlyTitleView.text = tr("hourly_title")
        chartTitleView.text = tr("chart_title")
        chartLegendView.text = tr("chart_legend")
        forecastDaysTitleView.text = tr("forecast_days_title")
        forecastDaysSubView.text = tr("forecast_days_sub", tempOffset)
        if (rawMinTempTomorrow == null) {
            statusText.text = tr("models_info")
            alertBanner.text = tr("analyzing_title")
            alertAdviceText.text = tr("analyzing_sub")
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleText = TextView(this).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSettingsView = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#475569"))
            }
            setOnClickListener { openSettingsDialog() }
        }
        topBar.addView(titleText)
        topBar.addView(btnSettingsView)
        root.addView(topBar)

        val cityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        cityText = TextView(this).apply {
            text = "📍 $currentCityName"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnFavHeart = TextView(this).apply {
            text = "🤍"
            textSize = 20f
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { toggleCurrentFavorite() }
        }
        cityRow.addView(cityText)
        cityRow.addView(btnFavHeart)
        badgeCalibrationText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(2))
        }
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(cityRow)
        root.addView(badgeCalibrationText)
        root.addView(statusText)

        btnGpsView = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) }
            setOnClickListener { requestGpsLocation() }
        }
        root.addView(btnGpsView)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        searchInput = EditText(this).apply {
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
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) }
        }
        btnSearchView = Button(this).apply {
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
        searchRow.addView(btnSearchView)
        root.addView(searchRow)

        favoritesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(favoritesContainer)

        val alertCard = makeCard("#1E293B", "#475569")
        alertBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        alertAdviceText = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        alertCard.addView(alertBanner)
        alertCard.addView(alertAdviceText)
        root.addView(alertCard)

        val mainCard = makeCard("#1E293B")
        labelMinTitle = TextView(this).apply {
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
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }
        radiativeMinText = TextView(this).apply {
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }
        hoursBelowZeroText = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
        }
        windMaxText = TextView(this).apply {
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
        }
        mainCard.addView(labelMinTitle)
        mainCard.addView(tempMinText)
        mainCard.addView(tempMinHourText)
        mainCard.addView(radiativeMinText)
        mainCard.addView(hoursBelowZeroText)
        mainCard.addView(windMaxText)
        root.addView(mainCard)

        val hourlyCard = makeCard("#1E293B")
        hourlyTitleView = TextView(this).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        hourlyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hourlyCard.addView(hourlyTitleView)
        hourlyCard.addView(hourlyContainer)
        root.addView(hourlyCard)

        val chartCard = makeCard("#1E293B")
        chartTitleView = TextView(this).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(2))
        }
        chartLegendView = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
        }
        barChartView = FrostBarChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chartCard.addView(chartTitleView)
        chartCard.addView(chartLegendView)
        chartCard.addView(barChartView)
        root.addView(chartCard)

        val daysCard = makeCard("#1E293B")
        forecastDaysTitleView = TextView(this).apply {
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(2))
        }
        forecastDaysSubView = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
        }
        forecastDaysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        daysCard.addView(forecastDaysTitleView)
        daysCard.addView(forecastDaysSubView)
        daysCard.addView(forecastDaysContainer)
        root.addView(daysCard)

        scroll.addView(root)
        setContentView(scroll)
        applyStaticLabels()
        updateBadgeText()
        updateFavoritesUI()
    }

    private fun updateBadgeText() {
        val parts = mutableListOf<String>()
        if (tempOffset != 0.0) parts.add(tr("badge_cal", tempOffset))
        if (notifEnabled) parts.add(tr("badge_alert", fmtTime(notifHour, notifMin)))
        if (parts.isEmpty()) {
            badgeCalibrationText.visibility = View.GONE
        } else {
            badgeCalibrationText.visibility = View.VISIBLE
            badgeCalibrationText.setTextColor(Color.parseColor("#FDE047"))
            badgeCalibrationText.text = parts.joinToString("  •  ")
        }
    }
    private fun openSettingsDialog() {
        var workingOffset = tempOffset
        var workingNotif = notifEnabled
        var workingHour = notifHour
        var workingMin = notifMin
        var workingLang = currentLang

        val dialogScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#1E293B")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
        }
        dialogScroll.addView(layout)

        val title = TextView(this).apply {
            text = StringsProvider.t(workingLang, "settings_title")
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        layout.addView(title)

        // Sélecteur de langue : Français | Italiano | English
        val langLabel = TextView(this).apply {
            text = StringsProvider.t(workingLang, "lang_label")
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        layout.addView(langLabel)

        val langItems = listOf("fr" to "🇫🇷 Français", "it" to "🇮🇹 Italiano", "en" to "🇬🇧 English")
        val langBtns = mutableListOf<Button>()
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        val calLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val calSub = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(6))
        }
        val chkNotif = CheckBox(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = workingNotif
        }
        val timeTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(6))
        }
        val notifSub = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, dp(2), 0, dp(10))
        }
        val btnTestNotif = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val btnSave = Button(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }

        val prefsSnap = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val snapCity = prefsSnap.getString("snap_city", null)
        val snapTime = prefsSnap.getString("snap_time", null)
        val prevForecastBox = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        fun updatePrevForecastText() {
            val lines = mutableListOf<String>()
            val cTemp = rawCurrentTemp
            if (cTemp != null) {
                val shortCurrCity = currentCityName.substringBefore("(").trim()
                val timeDisplay = if (workingLang == "fr") rawCurrentTime.replace(":", "h") else rawCurrentTime
                lines.add(
                    StringsProvider.t(
                        workingLang,
                        "curr_temp_info",
                        shortCurrCity,
                        timeDisplay,
                        cTemp,
                        cTemp + workingOffset
                    )
                )
            }
            if (snapCity != null && snapTime != null && prefsSnap.contains("snap_raw")) {
                val rRaw = prefsSnap.getFloat("snap_raw", 0f).toDouble()
                val rCal = prefsSnap.getFloat("snap_cal", 0f).toDouble()
                val rPot = prefsSnap.getFloat("snap_pot", 0f).toDouble()
                val shortCity = snapCity.substringBefore("(").trim()
                val snapTimeDisp = if (workingLang == "fr") snapTime.replace(":", "h") else snapTime
                lines.add(StringsProvider.t(workingLang, "prev_forecast_info", shortCity, snapTimeDisp, rRaw, rCal, rPot))
            } else {
                val nextCheckStr = if (workingNotif) String.format(Locale.US, "%02d:%02d", workingHour, workingMin) else "19:00"
                val nextDisp = if (workingLang == "fr") nextCheckStr.replace(":", "h") else nextCheckStr
                lines.add(StringsProvider.t(workingLang, "prev_forecast_none", nextDisp))
            }
            prevForecastBox.text = lines.joinToString("\n\n")
        }

        fun refreshDialogTexts() {
            title.text = StringsProvider.t(workingLang, "settings_title")
            langLabel.text = StringsProvider.t(workingLang, "lang_label")
            calLabel.text = StringsProvider.t(workingLang, "cal_label")
            calSub.text = StringsProvider.t(workingLang, "cal_sub")
            chkNotif.text = StringsProvider.t(workingLang, "chk_notif")
            timeTitle.text = StringsProvider.t(workingLang, "time_title")
            notifSub.text = StringsProvider.t(workingLang, "notif_sub")
            btnTestNotif.text = StringsProvider.t(workingLang, "btn_test_notif")
            btnSave.text = StringsProvider.t(workingLang, "btn_save")
            updatePrevForecastText()
            for (i in langItems.indices) {
                val sel = (langItems[i].first == workingLang)
                langBtns[i].background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (sel) "#0284C7" else "#334155"))
                    cornerRadius = dp(8).toFloat()
                    if (sel) setStroke(dp(2), Color.parseColor("#38BDF8"))
                }
                langBtns[i].setTextColor(if (sel) Color.WHITE else Color.parseColor("#CBD5E1"))
            }
        }

        for ((code, label) in langItems) {
            val b = Button(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) }
                setOnClickListener {
                    workingLang = code
                    refreshDialogTexts()
                }
            }
            langBtns.add(b)
            langRow.addView(b)
        }
        layout.addView(langRow)

        val sep0 = View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
        }
        layout.addView(sep0)
        layout.addView(calLabel)
        layout.addView(calSub)

        val valDisplay = TextView(this).apply {
            text = String.format(StringsProvider.getLocale(workingLang), "%+.1f °C", workingOffset)
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }
        layout.addView(valDisplay)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun makeStepBtn(label: String, delta: Double?, isReset: Boolean = false): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isReset) "#475569" else "#0284C7"))
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) }
                setOnClickListener {
                    workingOffset = if (isReset) 0.0 else ((workingOffset + (delta ?: 0.0)) * 10.0).roundToInt() / 10.0
                    valDisplay.text = String.format(StringsProvider.getLocale(workingLang), "%+.1f °C", workingOffset)
                    updatePrevForecastText()
                }
            }
        }
        btnRow.addView(makeStepBtn("-0.5°", -0.5))
        btnRow.addView(makeStepBtn("-0.1°", -0.1))
        btnRow.addView(makeStepBtn("0°", null, true))
        btnRow.addView(makeStepBtn("+0.1°", +0.1))
        btnRow.addView(makeStepBtn("+0.5°", +0.5))
        layout.addView(btnRow)
        layout.addView(prevForecastBox)

        val sep = View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        }
        layout.addView(sep)

        chkNotif.setOnCheckedChangeListener { _, isChecked ->
            workingNotif = isChecked
            if (isChecked) requestNotificationPermissionIfNeeded()
        }
        layout.addView(chkNotif)
        layout.addView(timeTitle)

        val slots = listOf(17 to 0, 17 to 30, 18 to 0, 18 to 30, 19 to 0)
        val slotBtns = mutableListOf<Button>()
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        fun updateSlotColors() {
            for (i in slots.indices) {
                val sel = (slots[i].first == workingHour && slots[i].second == workingMin)
                slotBtns[i].background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (sel) "#0284C7" else "#334155"))
                    cornerRadius = dp(8).toFloat()
                    if (sel) setStroke(dp(2), Color.parseColor("#38BDF8"))
                }
                slotBtns[i].setTextColor(if (sel) Color.WHITE else Color.parseColor("#CBD5E1"))
            }
        }
        for ((h, min) in slots) {
            val b = Button(this).apply {
                text = String.format(Locale.FRANCE, "%02dh%02d", h, min)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) }
                setOnClickListener {
                    workingHour = h
                    workingMin = min
                    if (!workingNotif) { workingNotif = true; chkNotif.isChecked = true }
                    updateSlotColors()
                }
            }
            slotBtns.add(b)
            timeRow.addView(b)
        }
        updateSlotColors()
        layout.addView(timeRow)
        layout.addView(notifSub)

        btnTestNotif.apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { bottomMargin = dp(12) }
            setOnClickListener {
                requestNotificationPermissionIfNeeded()
                saveSettings(workingOffset, workingNotif, workingHour, workingMin, workingLang)
                val testIntent = Intent(this@MainActivity, FrostAlertReceiver::class.java).apply {
                    action = "fr.vigiegel.agrumes.ACTION_CHECK_FROST"
                    putExtra("is_test", true)
                }
                sendBroadcast(testIntent)
                Toast.makeText(this@MainActivity, StringsProvider.t(workingLang, "toast_test_sent"), Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnTestNotif)

        val dialog = AlertDialog.Builder(this).setView(dialogScroll).create()
        btnSave.apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D9488"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
            setOnClickListener {
                saveSettings(workingOffset, workingNotif, workingHour, workingMin, workingLang)
                dialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    StringsProvider.t(workingLang, "toast_saved", workingOffset),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(btnSave)
        refreshDialogTexts()
        dialog.show()
    }
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERMISSION_REQ)
            }
        }
    }

    private fun refreshDisplayedData() {
        updateBadgeText()
        updateFavoritesUI()
        val baseMin = rawMinTempTomorrow ?: return
        val adjustedMin = baseMin + tempOffset

        labelMinTitle.text = if (tempOffset == 0.0) tr("min_title_raw") else tr("min_title_cal", tempOffset)

        var minRadiativePot = Double.MAX_VALUE
        var maxNightGust = 0.0
        var hoursUnderZero = 0

        for (pt in rawNightPoints) {
            val adjT2m = pt.rawTemp2m + tempOffset
            val adjPot = pt.rawPotTemp + tempOffset
            if (adjPot < minRadiativePot) minRadiativePot = adjPot
            if (pt.gust > maxNightGust) maxNightGust = pt.gust
            if (adjT2m <= 0.0) hoursUnderZero++
        }

        tempMinText.text = String.format(loc(), "%.1f °C", adjustedMin)
        tempMinHourText.text = if (tempOffset == 0.0) {
            tr("peak_hour_raw", rawMinTempHour)
        } else {
            tr("peak_hour_cal", rawMinTempHour, baseMin)
        }
        radiativeMinText.text = tr("radiative_pot", minRadiativePot)
        hoursBelowZeroText.text = tr("hours_below_zero", hoursUnderZero)
        windMaxText.text = tr("wind_max", maxNightGust.roundToInt())

        when {
            minRadiativePot > 3.0 -> {
                alertBanner.text = tr("alert_safe_title")
                alertBanner.setTextColor(Color.parseColor("#4ADE80"))
                alertAdviceText.text = tr("alert_safe_advice")
            }
            minRadiativePot > 0.0 -> {
                alertBanner.text = tr("alert_watch_title")
                alertBanner.setTextColor(Color.parseColor("#FACC15"))
                alertAdviceText.text = tr("alert_watch_advice")
            }
            minRadiativePot > -2.5 -> {
                alertBanner.text = tr("alert_mod_title")
                alertBanner.setTextColor(Color.parseColor("#FB923C"))
                alertAdviceText.text = tr("alert_mod_advice")
            }
            else -> {
                alertBanner.text = tr("alert_sev_title")
                alertBanner.setTextColor(Color.parseColor("#F87171"))
                alertAdviceText.text = tr("alert_sev_advice")
            }
        }

        hourlyContainer.removeAllViews()
        val chartBars = mutableListOf<FrostBarChartView.BarPoint>()
        for (pt in rawNightPoints) {
            val adjT2m = pt.rawTemp2m + tempOffset
            val adjPot = pt.rawPotTemp + tempOffset
            chartBars.add(FrostBarChartView.BarPoint(pt.timeStr.substringBefore(":") + "h", adjT2m, adjPot))
            val dayLabel = if (pt.isToday) tr("tonight") else tr("tomorrow")

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val tvHour = TextView(this@MainActivity).apply {
                text = "$dayLabel ${pt.timeStr}"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val tvTemp = TextView(this@MainActivity).apply {
                text = tr("shelter_prefix", adjT2m)
                setTextColor(
                    when {
                        adjT2m <= 0.0 -> Color.parseColor("#F87171")
                        adjT2m <= 2.5 -> Color.parseColor("#FACC15")
                        else -> Color.parseColor("#4ADE80")
                    }
                )
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvPot = TextView(this@MainActivity).apply {
                text = tr("pots_prefix", adjPot)
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
        barChartView.setData(chartBars, loc())
        forecastDaysSubView.text = tr("forecast_days_sub", tempOffset)
        forecastDaysContainer.removeAllViews()

        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE dd/MM", loc())

        for (day in rawMultiDays) {
            val adj2m = day.rawMin2m + tempOffset
            val adjPot = day.rawMinPot + tempOffset

            val (badgeTxt, badgeBg, badgeStroke, tempColor) = when {
                adjPot <= 0.0 -> listOf(tr("day_freeze"), "#450A0A", "#EF4444", "#F87171")
                adjPot <= 2.5 -> listOf(tr("day_risk"), "#451A03", "#F97316", "#FB923C")
                else -> listOf(tr("day_safe"), "#052E16", "#22C55E", "#4ADE80")
            }

            val niceDate = try {
                val parsed = inFmt.parse(day.dateStr)
                if (parsed != null) outFmt.format(parsed).replaceFirstChar { it.uppercase(loc()) } else day.dateStr
            } catch (_: Exception) { day.dateStr }

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(badgeBg))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.parseColor(badgeStroke))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            val tvDate = TextView(this@MainActivity).apply {
                text = niceDate
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
            }

            val tvTemps = TextView(this@MainActivity).apply {
                text = String.format(loc(), "%.1f° / %.1f°", adj2m, adjPot)
                setTextColor(Color.parseColor(tempColor))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
            }

            val tvStatus = TextView(this@MainActivity).apply {
                text = badgeTxt
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            }

            row.addView(tvDate)
            row.addView(tvTemps)
            row.addView(tvStatus)
            forecastDaysContainer.addView(row)
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {}
    }

    private fun queryGeoApiGouv(query: String): Triple<Double, Double, String>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val isPostal = query.all { it.isDigit() } && query.length == 5
        val apiUrl = if (isPostal) {
            "https://geo.api.gouv.fr/communes?codePostal=$encoded&fields=nom,codeDepartement,centre&limit=1"
        } else {
            "https://geo.api.gouv.fr/communes?nom=$encoded&fields=nom,codeDepartement,centre&boost=population&limit=1"
        }
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", "VigieGelAgrumes/1.5 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val obj = arr.getJSONObject(0)
        val nom = obj.getString("nom")
        if (!isPostal && !nom.equals(query, ignoreCase = true)) return null
        val dep = obj.optString("codeDepartement", "")
        val coords = obj.getJSONObject("centre").getJSONArray("coordinates")
        val lon = coords.getDouble(0)
        val lat = coords.getDouble(1)
        val label = if (dep.isNotEmpty()) "$nom ($dep)" else nom
        return Triple(lat, lon, label)
    }

    private fun queryOpenMeteoGeocoding(query: String): Triple<Double, Double, String>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val apiUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=5&language=$currentLang&format=json"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", "VigieGelAgrumes/1.5 (Android)")
        }
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        if (!json.has("results")) return null
        val results = json.getJSONArray("results")
        if (results.length() == 0) return null

        var bestItem = results.getJSONObject(0)
        for (i in 0 until results.length()) {
            val candidate = results.getJSONObject(i)
            val cName = candidate.optString("name", "")
            val cCode = candidate.optString("country_code", "")
            if (cName.equals(query, ignoreCase = true) && (cCode == "FR" || cCode == "IT")) {
                bestItem = candidate
                break
            }
        }

        val nom = bestItem.getString("name")
        val lat = bestItem.getDouble("latitude")
        val lon = bestItem.getDouble("longitude")
        val countryCode = bestItem.optString("country_code", "")
        val country = bestItem.optString("country", "")
        val postcodes = bestItem.optJSONArray("postcodes")

        val label = if (countryCode == "FR") {
            val dep = if (postcodes != null && postcodes.length() > 0) postcodes.getString(0).take(2) else ""
            if (dep.isNotEmpty()) "$nom ($dep)" else nom
        } else {
            val admin1 = bestItem.optString("admin1", "")
            when {
                country.isNotEmpty() && admin1.isNotEmpty() -> "$nom ($country - $admin1)"
                country.isNotEmpty() -> "$nom ($country)"
                else -> nom
            }
        }
        return Triple(lat, lon, label)
    }

    @Suppress("DEPRECATION")
    private fun queryAndroidGeocoder(query: String): Triple<Double, Double, String>? {
        val geocoder = Geocoder(this, loc())
        val list = geocoder.getFromLocationName(query, 1)
        if (list.isNullOrEmpty()) return null
        val addr = list[0]
        val city = addr.locality ?: addr.subAdminArea ?: addr.featureName ?: query
        val label = if (addr.countryCode == "FR") {
            val dep = addr.postalCode?.take(2) ?: ""
            if (dep.isNotEmpty()) "$city ($dep)" else city
        } else {
            val country = addr.countryName ?: ""
            if (country.isNotEmpty()) "$city ($country)" else city
        }
        return Triple(addr.latitude, addr.longitude, label)
    }

    private fun searchCityByName() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, tr("search_empty"), Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        statusText.text = tr("searching_city", query)
        executor.execute {
            var found: Triple<Double, Double, String>? = null
            val isFrPostal = query.all { it.isDigit() } && query.length == 5

            if (isFrPostal) {
                try { found = queryGeoApiGouv(query) } catch (_: Exception) {}
            }
            if (found == null) {
                try { found = queryOpenMeteoGeocoding(query) } catch (_: Exception) {}
            }
            if (found == null && !isFrPostal) {
                try { found = queryGeoApiGouv(query) } catch (_: Exception) {}
            }
            if (found == null) {
                try { found = queryAndroidGeocoder(query) } catch (_: Exception) {}
            }

            runOnUiThread {
                if (found != null) {
                    val (lat, lon, label) = found!!
                    searchInput.setText("")
                    val matchedFav = findMatchingFavorite(label, lat, lon)
                    tempOffset = matchedFav?.offset ?: 0.0
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat("offset", tempOffset.toFloat()).apply()
                    saveLastLocation(lat, lon, label)
                    fetchMeteoFranceData(lat, lon, label)
                } else {
                    Toast.makeText(this, tr("city_not_found", query), Toast.LENGTH_LONG).show()
                    statusText.text = tr("city_not_found", query)
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
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQ
                )
                return
            }
        }
        startFreshLocationLookup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQ && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFreshLocationLookup()
        } else if (requestCode == LOCATION_PERMISSION_REQ) {
            Toast.makeText(this, tr("gps_denied"), Toast.LENGTH_LONG).show()
            fetchMeteoFranceData(currentLat, currentLon, currentCityName)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFreshLocationLookup() {
        cityText.text = tr("gps_acquiring")
        statusText.text = tr("gps_querying")
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !netEnabled) {
            Toast.makeText(this, tr("gps_disabled"), Toast.LENGTH_LONG).show()
            statusText.text = tr("gps_disabled")
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
                        if (l != null && (fallbackLoc == null || l.time > fallbackLoc.time)) fallbackLoc = l
                    }
                }
                if (fallbackLoc != null) {
                    onLocationFound(fallbackLoc.latitude, fallbackLoc.longitude)
                } else {
                    statusText.text = tr("gps_weak")
                }
            }
        }, 6000L)
    }

    private fun onLocationFound(lat: Double, lon: Double) {
        executor.execute {
            val commune = reverseGeocodeCommune(lat, lon)
            runOnUiThread {
                val matchedFav = findMatchingFavorite(commune, lat, lon)
                tempOffset = matchedFav?.offset ?: 0.0
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat("offset", tempOffset.toFloat()).apply()
                saveLastLocation(lat, lon, commune)
                fetchMeteoFranceData(lat, lon, commune)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeCommune(lat: Double, lon: Double): String {
        try {
            val urlStr = String.format(Locale.US, "https://geo.api.gouv.fr/communes?lat=%.5f&lon=%.5f&fields=nom,codeDepartement", lat, lon)
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }
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
            val geocoder = Geocoder(this, loc())
            val list = geocoder.getFromLocation(lat, lon, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                val postal = addr.postalCode?.take(2) ?: ""
                if (!city.isNullOrEmpty()) {
                    return if (addr.countryCode == "FR" && postal.isNotEmpty()) "$city ($postal)" else city
                }
            }
        } catch (_: Exception) {}

        return String.format(Locale.US, "GPS (%.3f, %.3f)", lat, lon)
    }

    private fun fetchMeteoFranceData(lat: Double, lon: Double, label: String) {
        cityText.text = "📍 $label"
        updateFavoritesUI()
        statusText.text = tr("models_info")

        executor.execute {
            try {
                val apiUrl = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/meteofrance?latitude=%.4f&longitude=%.4f" +
                            "&current=temperature_2m&hourly=temperature_2m,dew_point_2m,wind_speed_10m,wind_gusts_10m,cloud_cover" +
                            "&daily=temperature_2m_min,temperature_2m_max&timezone=auto&forecast_days=7",
                    lat, lon
                )
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                var fetchedCurrTemp: Double? = null
                var fetchedCurrTime = "--:--"
                if (json.has("current")) {
                    val currObj = json.getJSONObject("current")
                    if (!currObj.isNull("temperature_2m")) {
                        fetchedCurrTemp = currObj.getDouble("temperature_2m")
                        val rawT = currObj.optString("time", "")
                        if (rawT.contains("T")) {
                            fetchedCurrTime = rawT.substringAfter("T")
                        }
                    }
                }

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
                val nightPoints = mutableListOf<RawHourPoint>()

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
                    val isTonightWindow = (tStr.startsWith(todayStr) && hourNum >= 18) || (tStr.startsWith(tomorrowStr) && hourNum <= 10)

                    if (isTonightWindow && !temps.isNull(i)) {
                        val t2m = temps.getDouble(i)
                        val wind = if (!winds.isNull(i)) winds.getDouble(i) else 10.0
                        val gust = if (!gusts.isNull(i)) gusts.getDouble(i) else wind
                        val cloud = if (!clouds.isNull(i)) clouds.getDouble(i) else 50.0

                        val clearSkyFactor = (100.0 - cloud) / 100.0
                        val calmWindFactor = if (wind < 8.0) (8.0 - wind) / 8.0 else 0.2
                        val radiativeDrop = 0.6 + (1.6 * clearSkyFactor * calmWindFactor)
                        val potTemp = t2m - radiativeDrop

                        nightPoints.add(RawHourPoint(tStr.startsWith(todayStr), tStr.substringAfter("T"), t2m, potTemp, gust))
                    }
                }

                val dailyMap2m = linkedMapOf<String, Double>()
                val dailyMapPot = linkedMapOf<String, Double>()

                for (i in 0 until times.length()) {
                    val tStr = times.getString(i)
                    val datePart = tStr.substringBefore("T")
                    if (datePart > todayStr && !temps.isNull(i)) {
                        val t2m = temps.getDouble(i)
                        val wind = if (!winds.isNull(i)) winds.getDouble(i) else 10.0
                        val cloud = if (!clouds.isNull(i)) clouds.getDouble(i) else 50.0
                        val clearSky = (100.0 - cloud) / 100.0
                        val calmWind = if (wind < 8.0) (8.0 - wind) / 8.0 else 0.2
                        val potTemp = t2m - (0.6 + 1.6 * clearSky * calmWind)

                        val prev2m = dailyMap2m[datePart]
                        if (prev2m == null || t2m < prev2m) dailyMap2m[datePart] = t2m

                        val prevPot = dailyMapPot[datePart]
                        if (prevPot == null || potTemp < prevPot) dailyMapPot[datePart] = potTemp
                    }
                }

                if (dailyMap2m.size < 6) {
                    try {
                        val fallbackUrl = String.format(
                            Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                                    "&hourly=temperature_2m,wind_speed_10m,cloud_cover&timezone=auto&forecast_days=7",
                            lat, lon
                        )
                        val fbConn = (URL(fallbackUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 7000
                            readTimeout = 7000
                        }
                        val fbJson = JSONObject(fbConn.inputStream.bufferedReader().use { it.readText() })
                        val fbHourly = fbJson.getJSONObject("hourly")
                        val fbTimes = fbHourly.getJSONArray("time")
                        val fbTemps = fbHourly.getJSONArray("temperature_2m")
                        val fbWinds = fbHourly.getJSONArray("wind_speed_10m")
                        val fbClouds = fbHourly.getJSONArray("cloud_cover")

                        val fbMap2m = linkedMapOf<String, Double>()
                        val fbMapPot = linkedMapOf<String, Double>()

                        for (i in 0 until fbTimes.length()) {
                            val tStr = fbTimes.getString(i)
                            val datePart = tStr.substringBefore("T")
                            if (datePart > todayStr && !dailyMap2m.containsKey(datePart) && !fbTemps.isNull(i)) {
                                val t2m = fbTemps.getDouble(i)
                                val wind = if (!fbWinds.isNull(i)) fbWinds.getDouble(i) else 10.0
                                val cloud = if (!fbClouds.isNull(i)) fbClouds.getDouble(i) else 50.0
                                val clearSky = (100.0 - cloud) / 100.0
                                val calmWind = if (wind < 8.0) (8.0 - wind) / 8.0 else 0.2
                                val potTemp = t2m - (0.6 + 1.6 * clearSky * calmWind)

                                val prev2m = fbMap2m[datePart]
                                if (prev2m == null || t2m < prev2m) fbMap2m[datePart] = t2m

                                val prevPot = fbMapPot[datePart]
                                if (prevPot == null || potTemp < prevPot) fbMapPot[datePart] = potTemp
                            }
                        }
                        for ((dStr, m2m) in fbMap2m) {
                            if (dailyMap2m.size < 6) {
                                dailyMap2m[dStr] = m2m
                                dailyMapPot[dStr] = fbMapPot[dStr] ?: m2m
                            }
                        }
                    } catch (_: Exception) {}
                }

                val multiDays = mutableListOf<RawDayPoint>()
                for ((dStr, m2m) in dailyMap2m) {
                    if (multiDays.size < 6) {
                        multiDays.add(RawDayPoint(dStr, m2m, dailyMapPot[dStr] ?: m2m))
                    }
                }

                val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (nowHour >= 17 && nightPoints.isNotEmpty() && minTempTomorrow != Double.MAX_VALUE) {
                    val pSnap = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val savedTarget = pSnap.getString("snap_target_date", "")
                    if (savedTarget != tomorrowStr) {
                        val minPotNight = nightPoints.minOf { it.rawPotTemp + tempOffset }
                        val snapLabel = if (notifEnabled) String.format(Locale.US, "%02d:%02d", notifHour, notifMin) else "19:00"
                        FrostAlertReceiver.saveForecastSnapshot(
                            this@MainActivity, tomorrowStr, label, snapLabel,
                            minTempTomorrow, minTempTomorrow + tempOffset, minPotNight
                        )
                    }
                }

                runOnUiThread {
                    rawCurrentTemp = fetchedCurrTemp
                    rawCurrentTime = fetchedCurrTime
                    rawMinTempTomorrow = minTempTomorrow
                    rawMinTempHour = minTempHour
                    rawNightPoints = nightPoints
                    rawMultiDays = multiDays
                    val timeNow = SimpleDateFormat("HH:mm", loc()).format(Date())
                    statusText.text = tr("status_updated", label, timeNow)
                    refreshDisplayedData()
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = tr("net_error") }
            }
        }
    }
}
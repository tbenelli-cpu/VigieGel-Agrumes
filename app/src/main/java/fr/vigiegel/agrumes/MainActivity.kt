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

    private lateinit var statusText: TextView
    private lateinit var cityText: TextView
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

    private var currentLat = 48.6644
    private var currentLon = 2.3514
    private var currentCityName = "Localisation en cours..."
    private var tempOffset = 0.0
    private var notifEnabled = false
    private var notifHour = 18
    private var notifMin = 0
    private fun fmtTime(h: Int, m: Int) = String.format(Locale.FRANCE, "%02dh%02d", h, m)

    data class RawHourPoint(
        val hourLabel: String,
        val rawTemp2m: Double,
        val rawPotTemp: Double,
        val gust: Double
    )
    private var rawMinTempTomorrow: Double? = null
    private var rawMinTempHour: String = "--h--"
    private var rawNightPoints = listOf<RawHourPoint>()

    companion object {
        private const val LOCATION_PERMISSION_REQ = 1001
        private const val NOTIF_PERMISSION_REQ = 1002
        const val PREFS_NAME = "VigieGelLocationPrefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tempOffset = ((prefs.getFloat("offset", 0.0f).toDouble() * 10.0).roundToInt() / 10.0)
        notifEnabled = prefs.getBoolean("notif_enabled", false)
        notifHour = prefs.getInt("notif_hour", 18)
        notifMin = prefs.getInt("notif_min", 0)

        buildUI()

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

    private fun saveSettings(newOffset: Double, newNotif: Boolean, newHour: Int = notifHour, newMin: Int = notifMin) {
        notifHour = newHour
        notifMin = newMin
        tempOffset = ((newOffset * 10.0).roundToInt() / 10.0)
        notifEnabled = newNotif
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("offset", tempOffset.toFloat())
            .putBoolean("notif_enabled", notifEnabled)
            .putInt("notif_hour", notifHour)
            .putInt("notif_min", notifMin)
            .apply()
        FrostAlertReceiver.scheduleDailyCheck(this, notifEnabled, notifHour, notifMin)
        refreshDisplayedData()
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

        // Barre supérieure : Titre à gauche + Bouton ⚙️ Paramètres à droite
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "❄️🍊 VigieGel Agrumes"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSettings = Button(this).apply {
            text = "⚙️ Paramètres"
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
        topBar.addView(title)
        topBar.addView(btnSettings)
        root.addView(topBar)

        cityText = TextView(this).apply {
            text = "📍 $currentCityName"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(2))
        }
        badgeCalibrationText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(2))
        }
        statusText = TextView(this).apply {
            text = "Modèles Météo-France AROME (1.3 km) & ARPEGE"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(cityText)
        root.addView(badgeCalibrationText)
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
        labelMinTitle = TextView(this).apply {
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
        mainCard.addView(labelMinTitle)
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
        updateBadgeText()
    }

    private fun updateBadgeText() {
        val parts = mutableListOf<String>()
        if (tempOffset != 0.0) {
            parts.add(String.format(Locale.FRANCE, "🌡️ Étalonnage jardin : %+.1f°C", tempOffset))
        }
        if (notifEnabled) {
            parts.add("🔔 Alerte ${fmtTime(notifHour, notifMin)} active")
        }
        if (parts.isEmpty()) {
            badgeCalibrationText.visibility = View.GONE
        } else {
            badgeCalibrationText.visibility = View.VISIBLE
            badgeCalibrationText.setTextColor(Color.parseColor("#FDE047"))
            badgeCalibrationText.text = parts.joinToString("  •  ")
        }
    }

    // Fenêtre dédiée ⚙️ Paramètres (Étalonnage au dixième de degré + Notification d\u0027alerte gel)
    private fun openSettingsDialog() {
        var workingOffset = tempOffset
        var workingNotif = notifEnabled
        var workingHour = notifHour
        var workingMin = notifMin

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        val title = TextView(this).apply {
            text = "⚙️ Paramètres VigieGel"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(14))
        }
        layout.addView(title)

        val calLabel = TextView(this).apply {
            text = "🌡️ Étalonnage sonde jardin (écart avec Météo-France)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val calSub = TextView(this).apply {
            text = "Ajustez par pas de 0,1°C ou 0,5°C (ex: -1,4°C pour votre jardin) :"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        }
        layout.addView(calLabel)
        layout.addView(calSub)

        val valDisplay = TextView(this).apply {
            text = String.format(Locale.FRANCE, "%+.1f °C", workingOffset)
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
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
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isReset) "#475569" else "#0284C7"))
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    workingOffset = if (isReset) 0.0 else ((workingOffset + (delta ?: 0.0)) * 10.0).roundToInt() / 10.0
                    valDisplay.text = String.format(Locale.FRANCE, "%+.1f °C", workingOffset)
                }
            }
        }

        btnRow.addView(makeStepBtn("-0.5°", -0.5))
        btnRow.addView(makeStepBtn("-0.1°", -0.1))
        btnRow.addView(makeStepBtn("0°", null, true))
        btnRow.addView(makeStepBtn("+0.1°", +0.1))
        btnRow.addView(makeStepBtn("+0.5°", +0.5))
        layout.addView(btnRow)

        // Séparateur
        val sep = View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(18)
                bottomMargin = dp(14)
            }
        }
        layout.addView(sep)

        // Case à cocher Notification d\u0027alerte gel
        val chkNotif = CheckBox(this).apply {
            text = "🔔 Activer l\u0027alerte gel quotidienne"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = workingNotif
            setOnCheckedChangeListener { _, isChecked ->
                workingNotif = isChecked
                if (isChecked) requestNotificationPermissionIfNeeded()
            }
        }
        val notifSub = TextView(this).apply {
            text = "Vérifie automatiquement la météo chaque soir vers 19h00 et envoie une alerte sur votre téléphone si la température corrigée sur les pots descend sous +2,0°C."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(dp(6), dp(4), 0, dp(12))
        }
        layout.addView(chkNotif)

        val timeTitle = TextView(this).apply {
            text = "⏰ Horaire de l'alerte (17h00 à 19h00) :"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(6))
        }
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
                text = fmtTime(h, min)
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

        val btnTestNotif = Button(this).apply {
            text = "📲 Tester l\u0027envoi d\u0027une notification maintenant"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(16)
            }
            setOnClickListener {
                requestNotificationPermissionIfNeeded()
                saveSettings(workingOffset, workingNotif, workingHour, workingMin)
                val testIntent = Intent(this@MainActivity, FrostAlertReceiver::class.java).apply {
                    action = "fr.vigiegel.agrumes.ACTION_CHECK_FROST"
                    putExtra("is_test", true)
                }
                sendBroadcast(testIntent)
                Toast.makeText(this@MainActivity, "Notification de test envoyée !", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnTestNotif)

        val dialog = AlertDialog.Builder(this)
            .setView(layout)
            .create()

        val btnSave = Button(this).apply {
            text = "✅ Enregistrer et fermer"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D9488"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            setOnClickListener {
                saveSettings(workingOffset, workingNotif, workingHour, workingMin)
                dialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    String.format(Locale.FRANCE, "Paramètres enregistrés (Étalonnage : %+.1f°C)", workingOffset),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(btnSave)
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
        val baseMin = rawMinTempTomorrow ?: return
        val adjustedMin = baseMin + tempOffset

        if (tempOffset == 0.0) {
            labelMinTitle.text = "TEMPÉRATURE MINIMALE DEMAIN MATIN (SOUS ABRI 2M)"
        } else {
            labelMinTitle.text = String.format(
                Locale.FRANCE,
                "TEMP. MINIMALE DEMAIN CORRIGÉE JARDIN (%+.1f°C)",
                tempOffset
            )
        }

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

        tempMinText.text = String.format(Locale.FRANCE, "%.1f °C", adjustedMin)
        tempMinHourText.text = if (tempOffset == 0.0) {
            "Pic minimal attendu demain vers $rawMinTempHour"
        } else {
            String.format(Locale.FRANCE, "Pic vers %s (Météo-France brut : %.1f°C)", rawMinTempHour, baseMin)
        }
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
        for (pt in rawNightPoints) {
            val adjT2m = pt.rawTemp2m + tempOffset
            val adjPot = pt.rawPotTemp + tempOffset

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
                text = String.format(Locale.FRANCE, "Abri: %.1f°C", adjT2m)
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
                text = String.format(Locale.FRANCE, "Pots: %.1f°C", adjPot)
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
            setRequestProperty("User-Agent", "VigieGelAgrumes/1.4 (Android)")
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
        val apiUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=5&language=fr&format=json"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", "VigieGelAgrumes/1.4 (Android)")
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
        val geocoder = Geocoder(this, Locale.FRANCE)
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
            Toast.makeText(this, "Saisissez le nom d\u0027une commune ou un code postal", Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        statusText.text = "Recherche de la commune \"$query\"..."
        executor.execute {
            var found: Triple<Double, Double, String>? = null
            var lastErr = ""
            val isFrPostal = query.all { it.isDigit() } && query.length == 5

            if (isFrPostal) {
                try { found = queryGeoApiGouv(query) } catch (e: Exception) { lastErr = e.message ?: "" }
            }
            if (found == null) {
                try { found = queryOpenMeteoGeocoding(query) } catch (e: Exception) { lastErr = e.message ?: "" }
            }
            if (found == null && !isFrPostal) {
                try { found = queryGeoApiGouv(query) } catch (e: Exception) { lastErr = e.message ?: "" }
            }
            if (found == null) {
                try { found = queryAndroidGeocoder(query) } catch (e: Exception) { lastErr = e.message ?: "" }
            }

            runOnUiThread {
                if (found != null) {
                    val (lat, lon, label) = found!!
                    searchInput.setText("")
                    saveLastLocation(lat, lon, label)
                    fetchMeteoFranceData(lat, lon, label)
                } else {
                    val detail = if (lastErr.isNotEmpty()) " ($lastErr)" else ""
                    Toast.makeText(this, "Commune introuvable : $query", Toast.LENGTH_LONG).show()
                    statusText.text = "Commune non trouvée$detail."
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
        } else if (requestCode == LOCATION_PERMISSION_REQ) {
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

                        val dayPrefix = if (tStr.startsWith(todayStr)) "Ce soir" else "Demain"
                        nightPoints.add(
                            RawHourPoint(
                                "$dayPrefix ${tStr.substringAfter("T")}",
                                t2m,
                                potTemp,
                                gust
                            )
                        )
                    }
                }

                runOnUiThread {
                    rawMinTempTomorrow = minTempTomorrow
                    rawMinTempHour = minTempHour
                    rawNightPoints = nightPoints
                    val timeNow = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
                    statusText.text = "Météo-France AROME ($label) • màj $timeNow"
                    refreshDisplayedData()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Erreur réseau : vérifiez votre connexion Internet (${e.localizedMessage})"
                }
            }
        }
    }
}

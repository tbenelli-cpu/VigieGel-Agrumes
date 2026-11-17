package fr.vigiegel.agrumes

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class FrostBarChartView(context: Context) : View(context) {

    data class BarPoint(
        val hourShort: String,
        val airTemp: Double,
        val potTemp: Double
    )

    private var points: List<BarPoint> = emptyList()
    private var currentLocale: Locale = Locale.FRANCE

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val potMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = dp(0.8f)
    }
    private val textTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = dp(8.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textHourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = dp(8.5f)
        textAlign = Paint.Align.CENTER
    }
    private val zeroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = dp(9f)
        textAlign = Paint.Align.LEFT
    }

    fun setData(newPoints: List<BarPoint>, locale: Locale) {
        points = newPoints
        currentLocale = locale
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = dp(195f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val padLeft = dp(10f)
        val padRight = dp(10f)
        val padTop = dp(22f)
        val padBottom = dp(22f)

        val chartW = width - padLeft - padRight
        val chartH = height - padTop - padBottom
        if (chartW <= 0f || chartH <= 0f) return

        var minVal = 0.0
        var maxVal = 4.0
        for (p in points) {
            minVal = min(minVal, min(p.airTemp, p.potTemp))
            maxVal = max(maxVal, max(p.airTemp, p.potTemp))
        }
        val yMin = floor(minVal - 1.0)
        val yMax = ceil(maxVal + 1.5)
        val span = max(1.0, yMax - yMin)

        fun valToY(v: Double): Float {
            val ratio = ((v - yMin) / span).toFloat()
            return padTop + chartH * (1f - ratio)
        }

        val yZero = valToY(0.0)
        canvas.drawLine(padLeft, padTop, width - padRight, padTop, gridPaint)
        canvas.drawLine(padLeft, padTop + chartH, width - padRight, padTop + chartH, gridPaint)

        if (yMin < 0.0 && yMax > 0.0) {
            canvas.drawLine(padLeft, yZero, width - padRight, yZero, zeroLinePaint)
            canvas.drawText("0°", padLeft + dp(2f), yZero - dp(3f), zeroLabelPaint)
        }

        val count = points.size
        val slotW = chartW / count
        val barW = slotW * 0.56f
        val radius = dp(3f)

        for (i in points.indices) {
            val pt = points[i]
            val cx = padLeft + slotW * (i + 0.5f)
            val left = cx - barW / 2f
            val right = cx + barW / 2f

            val yVal = valToY(pt.airTemp)
            val top = min(yVal, yZero)
            val bottom = max(yVal, yZero)

            barPaint.color = when {
                pt.airTemp <= 0.0 -> Color.parseColor("#EF4444")
                pt.airTemp <= 2.5 -> Color.parseColor("#FACC15")
                else -> Color.parseColor("#22C55E")
            }

            val rect = RectF(left, top, right, max(bottom, top + dp(2f)))
            canvas.drawRoundRect(rect, radius, radius, barPaint)

            // Repère bleu pour la température au niveau des pots (radiatif)
            val yPot = valToY(pt.potTemp)
            canvas.drawLine(left - dp(1f), yPot, right + dp(1f), yPot, potMarkerPaint)

            // Valeur numérique au-dessus (ou en-dessous si négatif)
            val tempStr = String.format(currentLocale, "%.0f°", pt.airTemp)
            val labelY = if (pt.airTemp >= 0.0) top - dp(4f) else bottom + dp(10f)
            canvas.drawText(tempStr, cx, labelY, textTempPaint)

            // Heure en bas (1 sur 2 si beaucoup de points pour garder une lecture aérée)
            if (count <= 10 || i % 2 == 0) {
                canvas.drawText(pt.hourShort, cx, height - dp(6f), textHourPaint)
            }
        }
    }
}
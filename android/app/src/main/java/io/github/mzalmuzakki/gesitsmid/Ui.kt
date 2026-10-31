package io.github.mzalmuzakki.gesitsmid

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny helpers for building screens in code (no layout XML, no libraries). */
object Ui {
    val BG = Color.rgb(12, 14, 18)
    val CARD = Color.rgb(24, 28, 36)
    val GREEN = Color.rgb(60, 200, 110)
    val MUTED = Color.rgb(140, 148, 160)

    fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** Scrollable page; returns (root view to setContentView, column to add things to). */
    fun page(a: Activity, title: String): Pair<View, LinearLayout> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 32))
        }
        col.addView(TextView(a).apply { text = title; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        return ScrollView(a).apply { setBackgroundColor(BG); addView(col) } to col
    }

    fun header(a: Activity, col: LinearLayout, s: String) = col.addView(TextView(a).apply {
        text = s; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN)
        setPadding(0, dp(a, 18), 0, dp(a, 6))
    })

    fun text(a: Activity, col: ViewGroup, s: String = "", size: Float = 14f, color: Int = Color.WHITE, mono: Boolean = false) =
        TextView(a).apply { text = s; textSize = size; setTextColor(color); if (mono) typeface = Typeface.MONOSPACE }.also { col.addView(it) }

    fun button(a: Activity, parent: ViewGroup, s: String, onClick: () -> Unit) =
        Button(a).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }.also { parent.addView(it) }

    fun row(a: Activity, col: LinearLayout) = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }.also { col.addView(it) }

    /** Rounded card with a label and a big value; returns the value TextView. */
    fun tile(a: Activity, parent: LinearLayout, label: String): TextView {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(a, 12).toFloat() }
        }
        card.addView(TextView(a).apply { text = label; textSize = 11f; setTextColor(MUTED) })
        val v = TextView(a).apply { textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) }
        card.addView(v)
        parent.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4))
        })
        return v
    }

    fun number(a: Activity, col: LinearLayout, label: String, value: Float): EditText {
        text(a, col, label, 12f, MUTED)
        return EditText(a).apply {
            setText(if (value % 1f == 0f) value.toInt().toString() else value.toString())
            setTextColor(Color.WHITE); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }.also { col.addView(it) }
    }
}

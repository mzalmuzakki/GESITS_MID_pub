package io.github.mzalmuzakki.gesitsmid

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Small design system built in code: dark theme, cards, buttons, switches, segmented choices. */
object Ui {
    val BG = Color.rgb(12, 14, 18)
    val CARD = Color.rgb(24, 28, 36)
    val CARD2 = Color.rgb(34, 40, 50)
    val GREEN = Color.rgb(60, 200, 110)
    val RED = Color.rgb(235, 70, 70)
    val AMBER = Color.rgb(255, 176, 32)
    val BLUE = Color.rgb(70, 145, 255)
    val TEXT = Color.rgb(236, 239, 243)
    val MUTED = Color.rgb(140, 148, 160)

    fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(strokeW, stroke)
    }

    private fun lp(a: Activity, top: Int = 6, bottom: Int = 6, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(a, top), 0, dp(a, bottom)) }

    /** Scrollable page; returns (root view, column to fill). */
    fun page(a: Activity, title: String? = null): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 24))
        }
        if (title != null) col.addView(TextView(a).apply {
            text = L.t(title); textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(TEXT)
            setPadding(dp(a, 4), dp(a, 6), 0, dp(a, 6))
        })
        return ScrollView(a).apply { setBackgroundColor(BG); isFillViewport = true; addView(col) } to col
    }

    /** Rounded card with an optional title; returns its content column. */
    fun card(a: Activity, parent: ViewGroup, title: String? = null, accent: Int? = null): LinearLayout {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 14))
            background = rounded(CARD, dp(a, 16).toFloat(), accent, if (accent != null) dp(a, 1) else 0)
        }
        if (title != null) c.addView(TextView(a).apply {
            text = L.t(title); textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent ?: MUTED)
            letterSpacing = 0.06f; setPadding(0, 0, 0, dp(a, 6))
        })
        parent.addView(c, lp(a))
        return c
    }

    fun text(a: Activity, parent: ViewGroup, s: String = "", size: Float = 14f, color: Int = TEXT,
             bold: Boolean = false, mono: Boolean = false) = TextView(a).apply {
        text = L.t(s); textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.15f)
    }.also { parent.addView(it) }

    private fun styled(a: Activity, b: Button, fill: Int, textColor: Int, stroke: Int?) = b.apply {
        isAllCaps = false; textSize = 15f; setTextColor(textColor); stateListAnimator = null
        background = rounded(fill, dp(a, 12).toFloat(), stroke, dp(a, 1))
        minHeight = dp(a, 48); setPadding(dp(a, 14), 0, dp(a, 14), 0)
    }

    /** Filled green button (main action). Weight > 0 = share a row. */
    fun primary(a: Activity, parent: ViewGroup, s: String, weight: Float = 0f, onClick: () -> Unit) =
        styled(a, Button(a), GREEN, Color.rgb(8, 20, 12), null).apply { text = L.t(s); setTypeface(typeface, Typeface.BOLD); setOnClickListener { onClick() } }
            .also { add(a, parent, it, weight) }

    /** Outlined button (secondary action). */
    fun secondary(a: Activity, parent: ViewGroup, s: String, weight: Float = 0f, color: Int = TEXT, onClick: () -> Unit) =
        styled(a, Button(a), CARD2, color, null).apply { text = L.t(s); setOnClickListener { onClick() } }.also { add(a, parent, it, weight) }

    private fun add(a: Activity, parent: ViewGroup, v: View, weight: Float) {
        if (weight > 0f) parent.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            setMargins(dp(a, 3), dp(a, 4), dp(a, 3), dp(a, 4))
        }) else parent.addView(v, lp(a, 4, 4))
    }

    fun row(a: Activity, parent: ViewGroup) = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }.also { parent.addView(it) }

    /** Title + description + switch on the right. */
    fun switchRow(a: Activity, parent: ViewGroup, title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(a, 6), 0, dp(a, 6)) }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(a).apply { text = L.t(title); textSize = 15f; setTextColor(TEXT) })
        if (desc != null) texts.addView(TextView(a).apply { text = L.t(desc); textSize = 12f; setTextColor(MUTED) })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(a).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(GREEN, MUTED))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.rgb(30, 90, 55), CARD2))
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        r.addView(sw)
        parent.addView(r)
        return sw
    }

    /** Segmented choice (like tabs); returns a function that selects a key from code. */
    fun segmented(a: Activity, parent: ViewGroup, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): (String) -> Unit {
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
            background = rounded(CARD2, dp(a, 12).toFloat())
        }
        val views = options.map { (key, label) ->
            TextView(a).apply {
                text = L.t(label); textSize = 13f; gravity = Gravity.CENTER; tag = key
                setPadding(dp(a, 4), dp(a, 10), dp(a, 4), dp(a, 10))
            }.also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        val select: (String) -> Unit = { key ->
            views.forEach { v ->
                val on = v.tag == key
                v.setTextColor(if (on) Color.rgb(8, 20, 12) else TEXT)
                v.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                v.background = if (on) rounded(GREEN, dp(a, 10).toFloat()) else null
            }
        }
        views.forEach { v -> v.setOnClickListener { select(v.tag as String); onSelect(v.tag as String) } }
        select(selected)
        parent.addView(bar, lp(a, 4, 4))
        return select
    }

    /** Rounded tile with a label and a big value; returns the value TextView. */
    fun tile(a: Activity, parent: LinearLayout, label: String, size: Float = 22f): TextView {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
            background = rounded(CARD2, dp(a, 12).toFloat())
        }
        c.addView(TextView(a).apply { text = L.t(label); textSize = 11f; setTextColor(MUTED); letterSpacing = 0.05f })
        val v = TextView(a).apply { textSize = size; setTypeface(typeface, Typeface.BOLD); setTextColor(TEXT); text = "--" }
        c.addView(v)
        parent.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
        })
        return v
    }

    fun input(a: Activity, parent: ViewGroup, hint: String, value: String = "", number: Boolean = false) = EditText(a).apply {
        this.hint = L.t(hint); setText(value); setTextColor(TEXT); setHintTextColor(MUTED); textSize = 15f
        inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        background = rounded(CARD2, dp(a, 10).toFloat()); setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10))
    }.also { parent.addView(it, lp(a, 4, 4)) }

    /** Number field with a label above it (for settings). */
    fun number(a: Activity, parent: ViewGroup, label: String, value: Float): EditText {
        text(a, parent, label, 12f, MUTED)
        return input(a, parent, label, if (value % 1f == 0f) value.toInt().toString() else value.toString(), number = true)
    }

    /** "Advanced" section that starts collapsed. Returns its content column. */
    fun collapsible(a: Activity, parent: ViewGroup, title: String): LinearLayout {
        val c = card(a, parent)
        val shown = L.t(title)
        val head = TextView(a).apply { text = "▸  $shown"; textSize = 15f; setTextColor(TEXT); setPadding(0, dp(a, 2), 0, dp(a, 2)) }
        c.addView(head)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(a, 8), 0, 0) }
        c.addView(body)
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            head.text = (if (open) "▾  " else "▸  ") + shown
        }
        return body
    }

    /** Section title (green). */
    fun header(a: Activity, parent: ViewGroup, s: String) = parent.addView(TextView(a).apply {
        text = L.t(s); textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN)
        setPadding(dp(a, 2), dp(a, 16), 0, dp(a, 6))
    })

    fun button(a: Activity, parent: ViewGroup, s: String, onClick: () -> Unit) = secondary(a, parent, s, onClick = onClick)
}

package name.boyle.chris.sgtpuzzles.config

import android.content.Context
import android.util.TypedValue
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.TableLayout
import android.widget.TableRow
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import name.boyle.chris.sgtpuzzles.R
import name.boyle.chris.sgtpuzzles.Utils.listFromSeparated
import name.boyle.chris.sgtpuzzles.backend.UsedByJNI

/** Expresses midend's config_items as Views. */
abstract class ConfigViewsBuilder(
    private val themedContext: Context,
    private val engine: ConfigBuilder.EngineCallbacks
) : ConfigBuilder {

    val table: TableLayout = TableLayout(themedContext).apply {
        val xPadding = context.resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal)
        val yPadding = context.resources.getDimensionPixelSize(R.dimen.dialog_padding_vertical)
        setPadding(xPadding, yPadding, xPadding, yPadding)
        setColumnShrinkable(0, true)
        setColumnShrinkable(1, true)
        setColumnStretchable(0, true)
        setColumnStretchable(1, true)
    }

    private val onApply = mutableListOf<() -> Unit>()

    fun configApply() {
        onApply.map { it() }
    }

    private fun Context.dip(dip: Float) =
        TypedValue.applyDimension(COMPLEX_UNIT_DIP, dip, resources.displayMetrics).toInt()

    private fun attachLabel(label: AppCompatTextView, labeled: View) {
        ViewCompat.setAccessibilityDelegate(labeled, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addLabeledBy(label)
            }
        })
    }

    fun addStringView(name: String, value: String): ConfigBuilder.TextRowParts {
        // An M3 text field: the label is the floating hint of the field,
        // so the field carries its own label for a screen reader and the
        // value is never separated from it. Outlined, like the rest of
        // the shell. The minimum width leaves room for the label, and the
        // field is 56dp tall, above the 48dp target.
        val input = TextInputLayout(themedContext).apply {
            hint = name
            minimumWidth =
                context.resources.getDimensionPixelSize(R.dimen.dialog_long_edit_text_width)
            layoutParams = TableRow.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { span = 2 }
        }
        val editText = TextInputEditText(input.context).apply {
            setText(value)
            setSelectAllOnFocus(true)
            onApply += { engine.configSetString(name, text.toString(), false) }
        }
        input.addView(editText)
        val tr = TableRow(themedContext).apply {
            addView(input)
            // The floating label of a field sits on its own outline, so
            // two fields need a gap to stay two fields.
            setPadding(0, 0, 0, context.dip(8f))
            gravity = Gravity.CENTER_VERTICAL
            table.addView(this)
        }
        return ConfigBuilder.TextRowParts(tr, input, editText)
    }

    fun addBooleanView(name: String, checked: Boolean): CheckBox {
        return MaterialCheckBox(themedContext).apply {
            text = name
            isChecked = checked
            minimumHeight = context.dip(48f)
            table.addView(this)
            onApply += { engine.configSetBool(name, isChecked, false) }
        }
    }

    @UsedByJNI
    override fun addChoices(whichEvent: Int, kw: String, name: String, choiceList: String, choiceKWList: String, selection: Int) {
        val choices = listFromSeparated(choiceList)
        val s = AppCompatSpinner(themedContext).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item, choices.toTypedArray()
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selection)
            // The spinner has no text of its own, so the label must be
            // part of its node rather than only a relation to a sibling.
            contentDescription = name
            layoutParams = TableRow.LayoutParams(
                context.resources.getDimensionPixelSize(R.dimen.dialog_spinner_width),
                context.dip(48f)
            )
            onApply += { engine.configSetChoice(name, selectedItemPosition, false) }
        }
        val tv = AppCompatTextView(themedContext).apply {
            text = name
            setPadding(
                0,
                0,
                context.resources.getDimensionPixelSize(R.dimen.dialog_padding_horizontal),
                0
            )
            gravity = Gravity.END
        }
        attachLabel(tv, s)
        val tr = TableRow(themedContext).apply {
            addView(tv)
            addView(s)
            gravity = Gravity.CENTER_VERTICAL
        }
        table.addView(tr)
    }
}
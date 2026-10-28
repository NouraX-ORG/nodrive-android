/*
 * NoDrive by Nourax
 *
 * SPDX-FileCopyrightText: 2026 Nourax
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.nourax.nodrive.conflicts

import android.app.Activity
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.owncloud.android.R

/** The words of each choice, and the one dialog both the Settings row and "Resolve all conflicts…" use. */
object ChoiceDialog {
    private const val SUMMARY_SIZE = 0.85f
    private const val PADDING_DP = 24
    private const val ROW_PADDING_DP = 12

    fun title(choice: ConflictChoice): Int = when (choice) {
        ConflictChoice.SMART -> R.string.nodrive_conflicts_choice_smart
        ConflictChoice.KEEP_NODRIVE -> R.string.nodrive_conflicts_choice_keep_nodrive
        ConflictChoice.KEEP_PHONE -> R.string.nodrive_conflicts_choice_keep_phone
        ConflictChoice.KEEP_BOTH -> R.string.nodrive_conflicts_choice_keep_both
        ConflictChoice.ASK -> R.string.nodrive_conflicts_choice_ask
    }

    fun summary(choice: ConflictChoice): Int? =
        if (choice == ConflictChoice.SMART) R.string.nodrive_conflicts_choice_smart_summary else null

    /** One line, for a Settings summary: "Smart (recommended)". */
    fun label(context: Context, choice: ConflictChoice): String = context.getString(title(choice))

    /** "Title" on the first line and, when the choice has one, its second line in smaller type. */
    fun rowText(context: Context, choice: ConflictChoice): CharSequence {
        val first = context.getString(title(choice))
        val second = summary(choice)?.let { context.getString(it) } ?: return first
        return SpannableString("$first\n$second").apply {
            setSpan(RelativeSizeSpan(SUMMARY_SIZE), first.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Shows the choices as radio buttons. With [applyLabel] the dialog has Apply and Cancel buttons, and Apply stays off
     * until a choice is made; without it, tapping a choice is the answer.
     */
    @Suppress("LongParameterList")
    fun show(
        activity: Activity,
        titleRes: Int,
        choices: List<ConflictChoice>,
        selected: ConflictChoice?,
        applyLabel: Int?,
        cancelLabel: Int,
        onCancel: () -> Unit = {},
        onChoice: (ConflictChoice) -> Unit
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density
        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            val padding = (PADDING_DP * density).toInt()
            setPadding(padding, (ROW_PADDING_DP * density).toInt(), padding, 0)
        }
        val ids = HashMap<Int, ConflictChoice>()
        choices.forEach { choice ->
            val button = MaterialRadioButton(activity).apply {
                id = View.generateViewId()
                text = rowText(activity, choice)
                tag = choice.key
                layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(paddingLeft, (ROW_PADDING_DP * density).toInt(), paddingRight, (ROW_PADDING_DP * density).toInt())
            }
            ids[button.id] = choice
            group.addView(button)
        }
        selected?.let { choice -> ids.entries.firstOrNull { it.value == choice }?.let { group.check(it.key) } }
        val scroll = ScrollView(activity).apply { addView(group) }
        val builder = MaterialAlertDialogBuilder(activity).setTitle(titleRes).setView(scroll)
        var dialog: AlertDialog? = null
        if (applyLabel == null) {
            builder.setNegativeButton(cancelLabel) { _, _ -> onCancel() }
            group.setOnCheckedChangeListener { _, checkedId ->
                ids[checkedId]?.let {
                    dialog?.dismiss()
                    onChoice(it)
                }
            }
        } else {
            builder.setPositiveButton(applyLabel) { _, _ ->
                ids[group.checkedRadioButtonId]?.let(onChoice)
            }
            builder.setNegativeButton(cancelLabel) { _, _ -> onCancel() }
            builder.setOnCancelListener { onCancel() }
        }
        val created = builder.create()
        dialog = created
        created.show()
        if (applyLabel != null) {
            val apply = created.getButton(AlertDialog.BUTTON_POSITIVE)
            apply.isEnabled = group.checkedRadioButtonId != View.NO_ID
            group.setOnCheckedChangeListener { _, _ -> apply.isEnabled = true }
        }
        return created
    }
}

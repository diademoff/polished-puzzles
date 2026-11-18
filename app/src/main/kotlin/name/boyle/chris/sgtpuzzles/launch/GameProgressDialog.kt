package name.boyle.chris.sgtpuzzles.launch

import android.content.Context
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import name.boyle.chris.sgtpuzzles.R
import name.boyle.chris.sgtpuzzles.databinding.ProgressDialogBinding
import name.boyle.chris.sgtpuzzles.ui.capReadingWidth

/**
 * Blocks the screen while a game is generated or resumed.
 *
 * A game generation can take a while, so the dialog is cancelable and,
 * after a few seconds, offers to reset the current type. The spinner and
 * the text are in the dialog body; the actions are Material buttons, so
 * the dialog matches the rest of the shell.
 */
class GameProgressDialog(
    context: Context,
    launch: GameLaunch,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    private val dialog: AlertDialog

    init {
        val binding = ProgressDialogBinding.inflate(LayoutInflater.from(context))
        binding.message.text = context.getString(
            if (launch.needsGenerating) R.string.starting else R.string.resuming,
        )
        val builder = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        if (launch.needsGenerating) {
            builder.setNeutralButton(
                context.getString(R.string.reset_this_backend, launch.whichBackend.displayName),
            ) { _, _ -> onReset() }
        }
        dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancel() }
        dialog.show()
        dialog.capReadingWidth()
        if (launch.needsGenerating) {
            revealResetAfterDelay()
        }
    }

    /**
     * A reset is a destructive action. It stays hidden for a few seconds,
     * so a fast generation never shows it and a stuck one still offers a
     * way out.
     */
    private fun revealResetAfterDelay() {
        val resetButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        resetButton.visibility = View.GONE
        val revealer = object : CountDownTimer(3000, 3000) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                if (dialog.isShowing) {
                    resetButton.visibility = View.VISIBLE
                }
            }
        }.start()
        dialog.setOnDismissListener { revealer.cancel() }
    }

    fun dismiss() {
        dialog.dismiss()
    }
}

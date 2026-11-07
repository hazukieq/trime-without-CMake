package com.hazukieq.hkrime.util

import android.app.ProgressDialog
import android.content.Context

fun createLoadingDialog(context: Context, textId: Int): ProgressDialog {
    @Suppress("DEPRECATION")
    return ProgressDialog(context).apply {
        setMessage(context.getText(textId))
        setCancelable(false)
    }
}

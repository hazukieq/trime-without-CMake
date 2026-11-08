/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hazukieq.hkrime.ime.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import com.hazukieq.hkrime.BuildConfig
import android.content.Intent
import android.content.IntentFilter
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.util.RimeUtils.deploy
import com.hazukieq.hkrime.util.RimeUtils.sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import timber.log.Timber

/** 接收 Intent 廣播事件  */
class IntentReceiver : BroadcastReceiver(), CoroutineScope by MainScope() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.action ?: return
        Timber.d("Received Command = %s", command)
        when (command) {
            COMMAND_DEPLOY -> launch {
                deploy(context)
            }
            COMMAND_SYNC -> async {
                sync(context)
            }
            COMMAND_DEBUG_TOGGLE_PANEL -> {
                // 測試用：adb shell am broadcast -a com.hazukieq.hkrime.debug.togglepanel
                com.hazukieq.hkrime.ime.core.Trime.getService()
                    ?.textInputManager?.toggleCandidatePanel()
            }
            COMMAND_DEBUG_CARET_PROBE -> {
                com.hazukieq.hkrime.ime.core.Trime.getService()
                    ?.textInputManager?.debugCaretProbe()
            }
            COMMAND_DEBUG_CARET_SCAN -> {
                com.hazukieq.hkrime.ime.core.Trime.getService()
                    ?.textInputManager?.debugCaretScan()
            }
            COMMAND_DEBUG_SHOW_PANEL -> {
                com.hazukieq.hkrime.ime.core.Trime.getService()
                    ?.textInputManager?.showCandidatePanel()
            }
            COMMAND_DEBUG_HIDE_PANEL -> {
                com.hazukieq.hkrime.ime.core.Trime.getService()
                    ?.textInputManager?.hideCandidatePanel()
            }
            Intent.ACTION_SHUTDOWN -> Rime.destroy()
            else -> return
        }
    }

    fun registerReceiver(context: Context) {
        context.registerReceiver(this, IntentFilter(COMMAND_DEPLOY))
        context.registerReceiver(this, IntentFilter(COMMAND_SYNC))
        context.registerReceiver(this, IntentFilter(Intent.ACTION_SHUTDOWN))
        context.registerReceiver(this, IntentFilter(COMMAND_DEBUG_TOGGLE_PANEL))
        context.registerReceiver(this, IntentFilter(COMMAND_DEBUG_CARET_PROBE))
        context.registerReceiver(this, IntentFilter(COMMAND_DEBUG_CARET_SCAN))
        context.registerReceiver(this, IntentFilter(COMMAND_DEBUG_SHOW_PANEL))
        context.registerReceiver(this, IntentFilter(COMMAND_DEBUG_HIDE_PANEL))
    }

    fun unregisterReceiver(context: Context) {
        context.unregisterReceiver(this)
    }

    companion object {
        private const val COMMAND_DEPLOY = "com.hazukieq.hkrime.deploy"
        private const val COMMAND_SYNC = "com.hazukieq.hkrime.sync"
        private const val COMMAND_DEBUG_TOGGLE_PANEL = "com.hazukieq.hkrime.debug.togglepanel"
        private const val COMMAND_DEBUG_CARET_PROBE = "com.hazukieq.hkrime.debug.caretprobe"
        private const val COMMAND_DEBUG_CARET_SCAN = "com.hazukieq.hkrime.debug.caretscan"
        private const val COMMAND_DEBUG_SHOW_PANEL = "com.hazukieq.hkrime.debug.showpanel"
        private const val COMMAND_DEBUG_HIDE_PANEL = "com.hazukieq.hkrime.debug.hidepanel"
    }
}

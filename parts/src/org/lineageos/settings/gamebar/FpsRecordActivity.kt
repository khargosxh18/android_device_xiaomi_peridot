/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.gamebar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.lineageos.settings.gamebar.ui.theme.GameBarComposeTheme

class FpsRecordActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GameBarComposeTheme {
                FpsRecordScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

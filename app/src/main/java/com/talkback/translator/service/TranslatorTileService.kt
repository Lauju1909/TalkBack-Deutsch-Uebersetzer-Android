package com.talkback.translator.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class TranslatorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val isRunning = TalkBackTranslationService.isServiceRunning()
        qsTile?.let { tile ->
            tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (isRunning) "Übersetzer AN" else "Übersetzer AUS"
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val isRunning = TalkBackTranslationService.isServiceRunning()
        if (isRunning) {
            TalkBackTranslationService.instance?.disableService()
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.label = "Übersetzer AUS"
            qsTile?.updateTile()
        } else {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}

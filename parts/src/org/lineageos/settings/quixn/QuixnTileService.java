/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.quixn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import org.lineageos.settings.R;

public class QuixnTileService extends TileService {

    private static final String TAG = "QuixnTileService";

    private static final String NOTIFICATION_CHANNEL_ID = "quixn_mode_channel";
    private static final int NOTIFICATION_ID_QUIXN = 2001;

    private NotificationManager mNotificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = getSystemService(NotificationManager.class);
        setupNotificationChannel();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState(QuixnUtils.isQuixnEnabled(this));
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean nextState = !QuixnUtils.isQuixnEnabled(this);
        QuixnUtils.setQuixnEnabled(this, nextState);

        if (nextState) {
            QuixnUtils.stopThermald();
            showQuixnWarningNotification();
        } else {
            QuixnUtils.startThermald();
            cancelQuixnWarningNotification();
        }

        updateTileState(nextState);
        Log.d(TAG, "Quixn Mode " + (nextState ? "enabled" : "disabled"));
    }

    private void updateTileState(boolean enabled) {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setLabel(getString(R.string.quixn_tile_title));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_quixn_mode));
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(enabled
                ? R.string.quixn_tile_subtitle_on
                : R.string.quixn_tile_subtitle_off));
        tile.updateTile();
    }

    private void setupNotificationChannel() {
        if (mNotificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.quixn_tile_title),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setBlockable(true);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showQuixnWarningNotification() {
        if (mNotificationManager == null) return;

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.quixn_notification_title))
                .setContentText(getString(R.string.quixn_notification_text))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(getString(R.string.quixn_notification_text)))
                .setSmallIcon(R.drawable.ic_quixn_mode)
                .setOngoing(true)
                .build();

        mNotificationManager.notify(NOTIFICATION_ID_QUIXN, notification);
    }

    private void cancelQuixnWarningNotification() {
        if (mNotificationManager != null) {
            mNotificationManager.cancel(NOTIFICATION_ID_QUIXN);
        }
    }
}

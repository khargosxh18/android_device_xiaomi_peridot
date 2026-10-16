/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.charge;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class ChargeTileService extends TileService {
    private static final String[] LABELS = {"OK", "COOL", "NUKE"};

    @Override
    public void onClick() {
        ChargeModeUtils.setMode((ChargeModeUtils.getMode() + 1) % ChargeModeUtils.MODE_COUNT);
        updateTile();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile != null) {
            int mode = ChargeModeUtils.getMode();
            tile.setLabel(LABELS[mode]);
            tile.setState(mode == ChargeModeUtils.MODE_OK ? Tile.STATE_INACTIVE
                                                          : Tile.STATE_ACTIVE);
            tile.updateTile();
        }
    }
}

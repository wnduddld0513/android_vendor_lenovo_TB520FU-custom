/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom;

import android.content.Context;
import android.os.Handler;

import com.tb520fu.input.InputExtension;
import com.tb520fu.input.Safe;

/**
 * Entry point of the optional customizations jar
 * (/system_ext/framework/tb520fu-input-custom.jar). InputCore loads it through
 * com.tb520fu.input.InputExtension; this class starts the game performance
 * enforcement inside system_server.
 */
public final class CustomInput implements InputExtension {
    private static final String TAG = "TB520FUCustom";

    private GamePerfController mGamePerf;

    @Override
    public void init(Context context, Handler handler) {
        mGamePerf = new GamePerfController(context, handler);
    }

    @Override
    public void start() {
        Safe.run("game performance", mGamePerf::start).run();
    }

    @Override
    public boolean handleKey(android.view.KeyEvent event) {
        return false;
    }
}

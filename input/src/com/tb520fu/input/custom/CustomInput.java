/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom;

import android.content.Context;
import android.os.Handler;

import com.tb520fu.input.InputExtension;
import com.tb520fu.input.Safe;
import com.tb520fu.input.custom.keybox.KeyboxSpoofService;

/**
 * Entry point of the optional customizations jar
 * (/system_ext/framework/tb520fu-input-custom.jar). InputCore loads it through
 * com.tb520fu.input.InputExtension; this class starts the game performance
 * enforcement inside system_server.
 */
public final class CustomInput implements InputExtension {
    private static final String TAG = "TB520FUCustom";

    private GamePerfController mGamePerf;
    private NotesPreinstall mNotes;
    private Context mContext;

    @Override
    public void init(Context context, Handler handler) {
        mContext = context.getApplicationContext();
        mGamePerf = new GamePerfController(context, handler);
        mNotes = new NotesPreinstall(context, handler);
        // Key attestation spoofing service ("Custom features" > Integrity);
        // it is inert until sys.tb520fu.integrity_teesim is 1.
        Safe.run("keybox spoof service",
                () -> KeyboxSpoofService.publish(context, handler)).run();
    }

    @Override
    public void start() {
        Safe.run("game performance", mGamePerf::start).run();
        // Installs Lenovo Notes from /system_ext/etc/preinstall once after the
        // first boot; a no-op on every later boot and after the user removes it.
        Safe.run("notes preinstall", mNotes::start).run();
        // Removes the fingerprint data of the disabled Play Integrity feature
        // (needs the settings provider, so it waits for the boot to finish).
        Safe.run("integrity cleanup",
                () -> KeyboxSpoofService.cleanupPifIfDisabled(mContext)).run();
    }

    @Override
    public boolean handleKey(android.view.KeyEvent event) {
        return false;
    }
}

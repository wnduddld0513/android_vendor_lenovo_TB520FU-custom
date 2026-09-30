/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.tb520fu.input.Safe;

import java.io.File;

/**
 * Installs Lenovo Notes (ZuiNotes) into /data as an ordinary, removable app the
 * first time the device boots.
 *
 * The APK is not a system app: it is shipped as a plain file at
 * {@link #APK_PATH} on read-only system_ext (see ZuiNotes/Android.bp) and the
 * APK is only ever read here. system_server installs it with a PackageInstaller
 * session, so it ends up exactly like a user-installed app - it can be updated
 * and uninstalled, and it never blocks a different signature (the stock Lenovo
 * ROM re-signs the APK, so an update would have to be re-signed by the same
 * maintainer key).
 *
 * Once the install succeeds {@link #SETTING} is set, which is what keeps the
 * app from coming back: if the user uninstalls it, the flag is still set and
 * nothing happens on the next boot. On failure the flag stays unset and the
 * next boot tries again.
 *
 * Everything runs on the worker handler and through Safe, so nothing here can
 * block or take down system_server. start() is called at LOCKED_BOOT_COMPLETED,
 * possibly before /data is mounted, so the install itself waits until user 0 is
 * unlocked.
 */
final class NotesPreinstall {

    private static final String TAG = "TB520FUNotes";
    private static final String SETTING = "tb520fu_notes_preinstalled";

    /** Package name of the stock Lenovo Notes app. */
    private static final String PACKAGE = "com.zui.notes";
    /** Plain file installed by the ZuiNotes/Android.bp prebuilt_etc module. */
    private static final String APK_PATH = "/system_ext/etc/preinstall/ZuiNotes.apk";

    /**
     * How long to wait for the PackageInstaller result before giving up and
     * retrying on the next boot. Generous: an APK verification agent gets 10 s
     * and this install also copies ~120 MB.
     */
    private static final long RESULT_TIMEOUT_MS = 10 * 60 * 1000;

    private final Context mContext;
    private final Handler mHandler;
    private boolean mInProgress;
    /** One-shot guard: the install is attempted at most once per boot. */
    private boolean mTriggered;
    /** Registered while user 0 is still locked, removed once it unlocks. */
    private BroadcastReceiver mUnlockReceiver;

    NotesPreinstall(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    void start() {
        if (Settings.Global.getInt(mContext.getContentResolver(), SETTING, 0) != 0) {
            return;
        }
        // start() runs at LOCKED_BOOT_COMPLETED. With a secure lock user 0 is
        // still locked then, so /data is not mounted yet; wait for the unlock
        // broadcast instead. The flag above is only read here, never written,
        // so a locked device simply tries again on the next boot.
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager != null && userManager.isUserUnlocked(UserHandle.SYSTEM)) {
            trigger();
            return;
        }
        mUnlockReceiver = Safe.receiver("notes unlock", (context, intent) -> {
            if (intent != null && intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                    UserHandle.USER_NULL) == UserHandle.USER_SYSTEM) {
                trigger();
            }
        });
        try {
            mContext.registerReceiver(mUnlockReceiver,
                    new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, mHandler,
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            Log.e(TAG, "could not register the user unlock receiver", t);
            return;
        }
        // User 0 may have unlocked between the check above and the
        // registration, and ACTION_USER_UNLOCKED is not sticky, so check once
        // more (the one-shot guard in trigger() makes this harmless).
        if (userManager != null && userManager.isUserUnlocked(UserHandle.SYSTEM)) {
            trigger();
        }
    }

    /** Runs the install once per boot, whichever path got us here. */
    private void trigger() {
        if (mTriggered) {
            return;
        }
        mTriggered = true;
        if (mUnlockReceiver != null) {
            try {
                mContext.unregisterReceiver(mUnlockReceiver);
            } catch (Throwable t) {
                Log.w(TAG, "could not unregister the user unlock receiver", t);
            }
            mUnlockReceiver = null;
        }
        mHandler.post(Safe.run("notes preinstall", this::install));
    }

    private void install() {
        if (mInProgress) {
            return;
        }
        if (Settings.Global.getInt(mContext.getContentResolver(), SETTING, 0) != 0) {
            return;
        }
        if (isInstalled()) {
            // Already a system app from an older build, or the user installed
            // it from the store: nothing to do, just remember it. If the user
            // uninstalls it later, the flag above keeps it away.
            Log.i(TAG, PACKAGE + " is already installed, marking as preinstalled");
            markPreinstalled();
            return;
        }

        File apk = new File(APK_PATH);
        if (!apk.isFile()) {
            Log.w(TAG, APK_PATH + " is missing, Lenovo Notes is not shipped with this build");
            return;
        }

        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setInstallReason(PackageManager.INSTALL_REASON_DEVICE_SETUP);
            params.setSize(apk.length());
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            // system_server may hard link from system_ext only if it is allowed
            // to link system_file; it is not, so this falls back to a copy.
            // Same as RollbackManager (Rollback.java).
            try {
                session.stageViaHardLink(APK_PATH);
            } catch (Exception e) {
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(apk,
                        ParcelFileDescriptor.MODE_READ_ONLY)) {
                    session.write("base.apk", 0, apk.length(), fd);
                }
            }

            final int id = sessionId;
            LocalIntentReceiver receiver = new LocalIntentReceiver(result -> mHandler.post(
                    Safe.run("notes preinstall result", () -> onResult(id, result))));
            session.commit(receiver.getIntentSender());
            mInProgress = true;
            mHandler.postDelayed(Safe.run("notes preinstall timeout",
                    () -> onTimeout(id)), RESULT_TIMEOUT_MS);
            Log.i(TAG, "installing " + PACKAGE + " from " + APK_PATH
                    + " (session " + sessionId + ")");
        } catch (Throwable t) {
            Log.e(TAG, "could not install " + PACKAGE, t);
            abandon(installer, sessionId);
        } finally {
            close(session);
        }
    }

    private void onResult(int sessionId, Intent result) {
        mInProgress = false;
        int status = result == null ? PackageInstaller.STATUS_FAILURE
                : result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "installed " + PACKAGE + ", preinstalled=" + SETTING);
            markPreinstalled();
        } else {
            // Leave the flag unset: the next boot tries again.
            Log.w(TAG, "install of " + PACKAGE + " failed: " + status + " "
                    + (result == null ? "" : result.getStringExtra(
                            PackageInstaller.EXTRA_STATUS_MESSAGE)));
        }
    }

    private void onTimeout(int sessionId) {
        if (!mInProgress) {
            return;
        }
        mInProgress = false;
        Log.w(TAG, "install of " + PACKAGE + " did not finish (session " + sessionId + ")");
    }

    private void markPreinstalled() {
        Settings.Global.putInt(mContext.getContentResolver(), SETTING, 1);
    }

    private boolean isInstalled() {
        try {
            PackageInfo info = mContext.getPackageManager().getPackageInfoAsUser(
                    PACKAGE, 0 /* flags */, android.os.UserHandle.USER_SYSTEM);
            return info != null && info.applicationInfo != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void abandon(PackageInstaller installer, int sessionId) {
        if (sessionId < 0) {
            return;
        }
        try {
            installer.abandonSession(sessionId);
        } catch (Throwable t) {
            Log.w(TAG, "could not abandon session " + sessionId, t);
        }
    }

    private static void close(PackageInstaller.Session session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Throwable t) {
            Log.w(TAG, "could not close session", t);
        }
    }

    /** {@code IntentSender} for a commit result, as used by RollbackManager. */
    private static final class LocalIntentReceiver {
        private final java.util.function.Consumer<Intent> mConsumer;
        private final IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType,
                    android.os.IBinder whitelistToken, IIntentReceiver finishedReceiver,
                    String requiredPermission, Bundle options) {
                mConsumer.accept(intent);
            }
        };

        LocalIntentReceiver(java.util.function.Consumer<Intent> consumer) {
            mConsumer = consumer;
        }

        IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }
    }
}

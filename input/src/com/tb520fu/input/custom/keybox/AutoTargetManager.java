/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom.keybox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tb520fu.input.Safe;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the attestation target list in step with the installed apps, the ROM
 * port of Specter's auto target (inotify on /data/app + a five minute poll):
 *
 *  - every newly installed app (third party, like `pm list packages -3`) is
 *    added to the target list,
 *  - apps that are uninstalled are dropped again (the default targets stay),
 *  - the same scan also runs every five minutes as the polling fallback and
 *    when the "add all installed apps" button is used.
 *
 * All of this is inert until the "add new apps automatically" switch is on
 * (KeyboxManager.autoTarget()); nothing scans or writes otherwise.
 */
final class AutoTargetManager {
    private static final String TAG = "TB520FUKeybox";
    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000L;

    private static final String[] KEEP = {
            "com.google.android.gms",
            "com.android.vending",
    };

    private AutoTargetManager() {}

    static void start(Context context, Handler handler) {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addDataScheme("package");
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String pkg = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
                    if (pkg == null || kGMS(pkg)) return;
                    if (!KeyboxManager.autoTarget()) return;
                    if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                        if (isThirdParty(ctx, pkg)) {
                            KeyboxManager.addTarget(pkg);
                            Log.i(TAG, "auto target added " + pkg);
                        }
                    } else {
                        removeIfSpare(pkg);
                    }
                }
            };
            context.registerReceiver(receiver, filter, null, handler,
                    Context.RECEIVER_EXPORTED);

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Safe.run("auto target poll", () -> sync(context)).run();
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }, POLL_INTERVAL_MS);
            Log.i(TAG, "auto target watcher started");
        } catch (Throwable t) {
            Log.e(TAG, "auto target watcher failed", t);
        }
    }

    /** One scan: add the installed third party apps, drop what is gone. */
    static int sync(Context context) {
        if (!KeyboxManager.autoTarget()) return 0;
        int changes = 0;

        // One enumeration builds both sets; no per-package lookups afterwards.
        PackageManager pm = context.getPackageManager();
        Set<String> installedAll = new HashSet<>();
        Set<String> installedThird = new HashSet<>();
        for (PackageInfo info : pm.getInstalledPackages(0)) {
            installedAll.add(info.packageName);
            ApplicationInfo app = info.applicationInfo;
            if (app == null) continue;
            if ((app.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                installedThird.add(info.packageName);
            }
        }
        for (String pkg : installedThird) {
            if (kGMS(pkg) || KeyboxManager.isTargeted(pkg)) continue;
            KeyboxManager.addTarget(pkg);
            changes++;
        }

        for (String pkg : KeyboxManager.targets()) {
            if (isKeep(pkg) || installedAll.contains(pkg)) continue;
            KeyboxManager.removeTarget(pkg);
            changes++;
        }
        if (changes > 0) Log.i(TAG, "auto target sync changed " + changes + " entries");
        return changes;
    }

    /** Adds every currently installed third party app (the UI button). */
    static int addAllInstalled(Context context) {
        int added = 0;
        PackageManager pm = context.getPackageManager();
        for (PackageInfo info : pm.getInstalledPackages(0)) {
            ApplicationInfo app = info.applicationInfo;
            if (app == null) continue;
            if ((app.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            if (kGMS(info.packageName) || KeyboxManager.isTargeted(info.packageName)) continue;
            KeyboxManager.addTarget(info.packageName);
            added++;
        }
        return added;
    }

    private static void removeIfSpare(String pkg) {
        if (isKeep(pkg)) return;
        if (KeyboxManager.isTargeted(pkg)) {
            KeyboxManager.removeTarget(pkg);
            Log.i(TAG, "auto target removed " + pkg);
        }
    }

    private static boolean kGMS(String pkg) {
        return pkg.equals("com.google.android.gms") || pkg.equals("com.android.vending");
    }

    private static boolean isKeep(String pkg) {
        for (String keep : KEEP) {
            if (keep.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean isThirdParty(Context context, String pkg) {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
            return (app.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}

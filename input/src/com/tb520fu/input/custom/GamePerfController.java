/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.tb520fu.input.Safe;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Per-app performance profiles, similar to the PRC game assistant (ZuiGameHelper)
 * performance modes. While a listed app is in the foreground its profile limits
 * or raises the CPU/GPU clocks; everything is restored when another app comes to
 * the front. Optionally background apps are killed when a listed app starts
 * (the game assistant's memory cleanup).
 *
 * The limits are written straight to cpufreq (system writable) and the kgsl
 * clock limits (made system writable in init.target.rc). Thermal throttling in
 * the kernel keeps working on top of these limits.
 *
 * CPU and GPU each have their own level per app: 0 power saving, 1 balanced,
 * 2 default (the stock limits), 3 custom (per-app percentages). Balanced caps
 * the prime cluster (single core), the other clusters (multi core) and the GPU
 * at 80 % of their maximum. Balanced and custom pick the frequency nearest to
 * the requested share of the cluster maximum from scaling_available_frequencies
 * at runtime.
 *
 * Settings.Global (TB520FUCustomFeatures):
 *   tb520fu_game_perf         1 enable profiles
 *   tb520fu_game_perf_apps    "pkg:cpu:gpu;pkg:cpu:gpu"
 *   tb520fu_game_perf_custom  "pkg:single:multi:gpu;pkg:..." custom percentages
 *   tb520fu_game_mem_clean    1 kill background apps when a listed app starts
 *
 * Uninstalled apps are dropped from the list; an empty list deletes the setting.
 */
public final class GamePerfController {
    private static final String TAG = "TB520FUGamePerf";

    static final String SETTING_ENABLED = "tb520fu_game_perf";
    static final String SETTING_APPS = "tb520fu_game_perf_apps";
    static final String SETTING_CUSTOM = "tb520fu_game_perf_custom";
    static final String SETTING_MEM_CLEAN = "tb520fu_game_mem_clean";

    static final int LEVEL_POWER_SAVING = 0;
    static final int LEVEL_BALANCED = 1;
    static final int LEVEL_DEFAULT = 2;
    static final int LEVEL_CUSTOM = 3;
    private static final int LEVEL_NONE = -1;

    /** Balanced keeps the prime core, the other cores and the GPU at this share. */
    private static final int BALANCED_PERCENT = 80;
    /** Custom profile bounds, mirrored by TB520FUCustomFeatures (GameApps.kt). */
    private static final int CUSTOM_MIN_PERCENT = 30;
    private static final int CUSTOM_DEFAULT_PERCENT = 80;
    private static final int CUSTOM_SINGLE = 0;
    private static final int CUSTOM_MULTI = 1;
    private static final int CUSTOM_GPU = 2;

    private static final String CPUFREQ = "/sys/devices/system/cpu/cpufreq/policy";
    private static final String KGSL = "/sys/class/kgsl/kgsl-3d0/";
    // SM8650: policy0 A520 (2.27 GHz), policy2 A720 (3.15), policy5 A720 (2.96), policy7 X4 (3.3)
    private static final int[] POLICIES = {0, 2, 5, 7};
    /** Index in POLICIES of the prime cluster; the single core limit applies to it. */
    private static final int PRIME = POLICIES.length - 1;

    // kHz caps per policy (0 = no cap) for power saving. Only the upper limit is
    // changed; the floors set up by the boot scripts stay as they are.
    private static final int[] POWER_SAVING_KHZ = {1804800, 2016000, 2016000, 2169600};
    // GPU clock cap in MHz for power saving (0 = no cap), applied with
    // max_clock_mhz (max_pwrlevel does not move the devfreq limit). 903 MHz is
    // the top level.
    private static final int POWER_SAVING_GPU_MHZ = 578;

    private static final long MEM_CLEAN_INTERVAL_MS = 5 * 60 * 1000;

    private final Context mContext;
    private final Handler mHandler;
    /** package -> {cpu level, gpu level} */
    private final Map<String, int[]> mApps = new HashMap<>();
    /** package -> {single %, multi %, gpu %}, used by LEVEL_CUSTOM */
    private final Map<String, int[]> mCustom = new HashMap<>();
    private final Map<String, Long> mLastClean = new HashMap<>();
    private boolean mEnabled;
    private boolean mMemClean;
    private int mAppliedCpu = LEVEL_NONE;
    private int mAppliedGpu = LEVEL_NONE;
    private int[] mAppliedCustom;
    private String mForeground;
    /** scaling_min/max as set up by the boot scripts, restored when no profile applies. */
    private final int[] mBaseMin = new int[POLICIES.length];
    private final int[] mBaseMax = new int[POLICIES.length];
    private int mGpuBaseMax;

    private final Runnable mCheckForeground = Safe.run("game perf foreground", this::checkForeground);

    public GamePerfController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    void start() {
        ContentResolver cr = mContext.getContentResolver();
        ContentObserver observer = Safe.observer(mHandler, "game perf settings", uri -> {
            readSettings();
            mForeground = null;
            checkForeground();
        });
        for (String key : new String[] {SETTING_ENABLED, SETTING_APPS, SETTING_CUSTOM, SETTING_MEM_CLEAN}) {
            cr.registerContentObserver(Settings.Global.getUriFor(key), false, observer);
        }
        readSettings();
        IntentFilter removed = new IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        removed.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Safe.run("game perf package removed", () -> {
                    if (intent.getData() != null) forget(intent.getData().getSchemeSpecificPart());
                }).run();
            }
        }, removed, null, mHandler);
        try {
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskStackChanged() {
                    // binder thread: debounce onto our handler
                    mHandler.removeCallbacks(mCheckForeground);
                    mHandler.postDelayed(mCheckForeground, 300);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "registerTaskStackListener", e);
        }
    }

    private void readSettings() {
        ContentResolver cr = mContext.getContentResolver();
        mEnabled = Settings.Global.getInt(cr, SETTING_ENABLED, 0) != 0;
        mMemClean = Settings.Global.getInt(cr, SETTING_MEM_CLEAN, 0) != 0;
        mApps.clear();
        String list = Settings.Global.getString(cr, SETTING_APPS);
        if (!TextUtils.isEmpty(list)) {
            for (String entry : list.split(";")) {
                String[] f = entry.split(":");
                if (f.length != 3 || f[0].isEmpty()) continue;
                try {
                    mApps.put(f[0], new int[] {
                            clampLevel(Integer.parseInt(f[1])), clampLevel(Integer.parseInt(f[2]))});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        mCustom.clear();
        String custom = Settings.Global.getString(cr, SETTING_CUSTOM);
        if (!TextUtils.isEmpty(custom)) {
            for (String entry : custom.split(";")) {
                String[] f = entry.split(":");
                if (f.length != 4 || f[0].isEmpty()) continue;
                try {
                    mCustom.put(f[0], new int[] {
                            clampPercent(Integer.parseInt(f[1])),
                            clampPercent(Integer.parseInt(f[2])),
                            clampPercent(Integer.parseInt(f[3]))});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!mEnabled) apply(LEVEL_NONE, LEVEL_NONE, null);
    }

    /** Drops an uninstalled app from the saved list and its custom profile. */
    private void forget(String pkg) {
        mLastClean.remove(pkg);
        boolean listed = mApps.remove(pkg) != null;
        boolean custom = mCustom.remove(pkg) != null;
        if (!listed && !custom) return;
        if (listed) saveApps();
        if (custom) saveCustom();
        Log.i(TAG, "removed uninstalled " + pkg);
    }

    private void saveApps() {
        StringJoiner list = new StringJoiner(";");
        for (Map.Entry<String, int[]> e : mApps.entrySet()) {
            list.add(e.getKey() + ":" + e.getValue()[0] + ":" + e.getValue()[1]);
        }
        putOrDelete(SETTING_APPS, mApps.isEmpty() ? null : list.toString());
    }

    private void saveCustom() {
        StringJoiner list = new StringJoiner(";");
        for (Map.Entry<String, int[]> e : mCustom.entrySet()) {
            list.add(e.getKey() + ":" + e.getValue()[0] + ":" + e.getValue()[1]
                    + ":" + e.getValue()[2]);
        }
        putOrDelete(SETTING_CUSTOM, mCustom.isEmpty() ? null : list.toString());
    }

    private void putOrDelete(String key, String value) {
        ContentResolver cr = mContext.getContentResolver();
        if (value == null) {
            cr.call(Settings.Global.CONTENT_URI, Settings.CALL_METHOD_DELETE_GLOBAL, key, null);
        } else {
            Settings.Global.putString(cr, key, value);
        }
    }

    private static int clampLevel(int level) {
        return Math.max(LEVEL_POWER_SAVING, Math.min(LEVEL_CUSTOM, level));
    }

    private static int clampPercent(int percent) {
        return Math.max(CUSTOM_MIN_PERCENT, Math.min(100, percent));
    }

    private void checkForeground() {
        String pkg = null;
        try {
            ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info != null && info.topActivity != null) pkg = info.topActivity.getPackageName();
        } catch (Exception e) {
            Log.w(TAG, "focused task", e);
        }
        if (pkg == null || pkg.equals(mForeground)) return;
        mForeground = pkg;
        int[] levels = mEnabled ? mApps.get(pkg) : null;
        if (levels != null) {
            apply(levels[0], levels[1], mCustom.get(pkg));
        } else {
            apply(LEVEL_NONE, LEVEL_NONE, null);
        }
        if (levels != null && mMemClean) cleanMemory(pkg);
    }

    private void apply(int cpu, int gpu, int[] custom) {
        boolean customChanged = !Arrays.equals(custom, mAppliedCustom);
        if (cpu != mAppliedCpu || (cpu == LEVEL_CUSTOM && customChanged)) applyCpu(cpu, custom);
        if (gpu != mAppliedGpu || (gpu == LEVEL_CUSTOM && customChanged)) applyGpu(gpu, custom);
        mAppliedCustom = custom == null ? null : custom.clone();
    }

    private void applyCpu(int level, int[] custom) {
        Log.i(TAG, "cpu " + mAppliedCpu + " -> " + level + " for " + mForeground);
        if (mAppliedCpu == LEVEL_NONE) {
            // Leaving the untouched state: remember what the boot scripts set up
            // (read late, so any boot-time boost is long gone).
            for (int i = 0; i < POLICIES.length; i++) {
                mBaseMin[i] = readInt(CPUFREQ + POLICIES[i] + "/scaling_min_freq");
                mBaseMax[i] = readInt(CPUFREQ + POLICIES[i] + "/scaling_max_freq");
            }
        }
        mAppliedCpu = level;
        for (int i = 0; i < POLICIES.length; i++) {
            String base = CPUFREQ + POLICIES[i] + "/";
            int hwMin = readInt(base + "cpuinfo_min_freq");
            int hwMax = mBaseMax[i];
            if (hwMin <= 0 || hwMax <= 0 || mBaseMin[i] <= 0) continue;
            int max = hwMax;
            if (level == LEVEL_POWER_SAVING) {
                if (POWER_SAVING_KHZ[i] > 0) max = Math.min(hwMax, POWER_SAVING_KHZ[i]);
            } else if (level == LEVEL_BALANCED) {
                max = percentMax(i, BALANCED_PERCENT, hwMax);
            } else if (level == LEVEL_CUSTOM) {
                // The prime cluster is the single core limit, the rest is multi core.
                int percent = customPercent(custom, i == PRIME ? CUSTOM_SINGLE : CUSTOM_MULTI);
                max = percentMax(i, percent, hwMax);
            }
            int min = Math.min(max, mBaseMin[i]);
            // order matters: never let min exceed the current max
            write(base + "scaling_min_freq", hwMin);
            write(base + "scaling_max_freq", max);
            write(base + "scaling_min_freq", min);
        }
    }

    /** Highest available frequency at or nearest to {@code percent} of the cluster max. */
    private int percentMax(int policy, int percent, int fallbackMax) {
        // 100 % is no limit at all (the boost clock, e.g. 3.3 GHz on the X4,
        // is not in scaling_available_frequencies).
        if (percent >= 100 || fallbackMax <= 0) return fallbackMax;
        int[] available = readInts(CPUFREQ + POLICIES[policy] + "/scaling_available_frequencies");
        // Percent of the real maximum (fallbackMax, the boot-time scaling_max_freq),
        // not of the highest listed frequency.
        int top = fallbackMax;
        int target = (int) ((long) top * percent / 100);
        int best = 0;
        for (int f : available) {
            if (best == 0 || Math.abs(f - target) < Math.abs(best - target)) best = f;
        }
        if (best <= 0) best = target;
        return Math.min(fallbackMax, best);
    }

    private static int customPercent(int[] custom, int index) {
        if (custom == null) return CUSTOM_DEFAULT_PERCENT;
        return clampPercent(custom[index]);
    }

    private void applyGpu(int level, int[] custom) {
        Log.i(TAG, "gpu " + mAppliedGpu + " -> " + level + " for " + mForeground);
        if (mAppliedGpu == LEVEL_NONE) {
            // Leaving the untouched state: remember the device limit
            mGpuBaseMax = readInt(KGSL + "max_clock_mhz");
        }
        mAppliedGpu = level;
        if (mGpuBaseMax <= 0) return;
        int max = mGpuBaseMax;
        if (level == LEVEL_POWER_SAVING) {
            if (POWER_SAVING_GPU_MHZ > 0) max = Math.min(max, POWER_SAVING_GPU_MHZ);
        } else if (level == LEVEL_BALANCED) {
            max = mGpuBaseMax * BALANCED_PERCENT / 100;
        } else if (level == LEVEL_CUSTOM) {
            max = mGpuBaseMax * customPercent(custom, CUSTOM_GPU) / 100;
        }
        write(KGSL + "max_clock_mhz", max);
    }

    /** Game assistant memory cleanup: kill cached/background apps before the game grows. */
    private void cleanMemory(String pkg) {
        long now = SystemClock.elapsedRealtime();
        Long last = mLastClean.get(pkg);
        if (last != null && now - last < MEM_CLEAN_INTERVAL_MS) return;
        mLastClean.put(pkg, now);
        try {
            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            am.getMemoryInfo(before);
            android.app.ActivityManager.getService().killAllBackgroundProcesses();
            Log.i(TAG, "memory cleanup for " + pkg + ", avail was " + (before.availMem >> 20) + " MB");
        } catch (Exception e) {
            Log.w(TAG, "memory cleanup", e);
        }
    }

    private static int readInt(String path) {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(Paths.get(path)),
                    StandardCharsets.US_ASCII).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Space or newline separated integers, empty on any error. */
    private static int[] readInts(String path) {
        try {
            String[] parts = new String(Files.readAllBytes(Paths.get(path)),
                    StandardCharsets.US_ASCII).trim().split("\\s+");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
            return out;
        } catch (Exception e) {
            return new int[0];
        }
    }

    private static void write(String path, int value) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(Integer.toString(value));
        } catch (IOException e) {
            Log.w(TAG, "write " + path + " = " + value + ": " + e.getMessage());
        }
    }
}

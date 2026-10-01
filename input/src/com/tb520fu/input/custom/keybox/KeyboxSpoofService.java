/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.tb520fu.input.custom.keybox;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * The ROM port of TEESimulator-RS / TrickyStore, run inside system_server.
 *
 * Published as the binder service "tb520fu.keybox" by CustomInput (the
 * tb520fu-input-custom.jar InputCore loads). The framework keystore client in
 * every app process calls PATCH when it reads an attestation certificate chain;
 * the service rewrites the chain with the active keybox and returns it. The
 * keybox private keys never leave this process.
 *
 * The Custom features app manages the keybox (a pool of spare keyboxes, the
 * renewal, the target list and the fingerprint data) through the admin
 * transactions. Patching is only active while sys.tb520fu.integrity_teesim is
 * 1 (snapshot of the switch, see init.tb520fu.integrity.rc); with the feature
 * off nothing is read or served.
 */
public final class KeyboxSpoofService {
    public static final String SERVICE = "tb520fu.keybox";

    private static final String TAG = "TB520FUKeybox";
    private static final String PROP_TEESIM = "sys.tb520fu.integrity_teesim";
    private static final String ADMIN_PACKAGE = "com.tb520fu.customfeatures";

    private static final int TRANSACT_STATUS = 1;
    private static final int TRANSACT_PATCH = 2;
    private static final int TRANSACT_INSTALL = 3;
    private static final int TRANSACT_CLEAR = 4;
    private static final int TRANSACT_SET_TARGETS = 5;
    private static final int TRANSACT_PUT_PIF = 6;
    private static final int TRANSACT_CLEAR_PIF = 7;
    private static final int TRANSACT_STORE = 8;
    private static final int TRANSACT_POOL = 9;
    private static final int TRANSACT_ROTATE = 10;
    private static final int TRANSACT_MARK_REVOKED = 11;
    private static final int TRANSACT_SET_AUTO_TARGET = 12;
    private static final int TRANSACT_ADD_ALL_INSTALLED = 13;

    private static final int RESULT_NONE = 0;
    private static final int RESULT_PATCHED = 1;
    private static final int RESULT_ERROR = -1;

    private KeyboxSpoofService() {}

    /** Called from CustomInput.init() on the tb520fu-input worker thread. */
    public static void publish(Context context, Handler handler) {
        try {
            Context appContext = context.getApplicationContext();
            cleanupIfDisabled(appContext);
            ServiceManager.addService(SERVICE, new Service(appContext));
            AutoTargetManager.start(appContext, handler);
            Log.i(TAG, "keybox spoof service published");
        } catch (Throwable t) {
            Log.e(TAG, "cannot publish keybox spoof service", t);
        }
    }

    /**
     * The boot snapshot of the switches governs everything; when a feature is
     * off after a restart, nothing of it may stay behind:
     *
     *  - TEE simulator off: the target list and the automatic target state are
     *    removed (and, when the keybox renewal is off as well, the keyboxes and
     *    the revocation records too).
     */
    private static void cleanupIfDisabled(Context context) {
        if (!SystemProperties.getBoolean(PROP_TEESIM, true)) {
            KeyboxManager.wipeTargets();
            if (!SystemProperties.getBoolean("sys.tb520fu.integrity_specter", false)) {
                KeyboxManager.clearAll();
            }
            Log.i(TAG, "TEE simulator off, target list and keyboxes cleaned");
        }
    }

    /**
     * Called from CustomInput.start() once the system has booted (the settings
     * provider does not exist when the service is published): clears the
     * fingerprint data of the disabled Play Integrity feature.
     */
    public static void cleanupPifIfDisabled(Context context) {
        if (SystemProperties.getBoolean("sys.tb520fu.integrity_pif", false)) return;
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.PIF_DATA, null);
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.FETCHED_PIF, null);
            Log.i(TAG, "Play Integrity fingerprint off, data cleared");
        } catch (Throwable t) {
            Log.w(TAG, "PIF cleanup failed", t);
        }
    }

    private static boolean enabled() {
        // Unset means on: init.tb520fu.integrity.rc snapshots the switch with a
        // default of 1, and only an explicit 0 turns the TEE simulator off.
        return SystemProperties.getBoolean(PROP_TEESIM, true);
    }

    private static int patchLevel() {
        String patch = SystemProperties.get("ro.build.version.security_patch", "");
        try {
            String[] parts = patch.split("-");
            return Integer.parseInt(parts[0]) * 100 + Integer.parseInt(parts[1]);
        } catch (Throwable t) {
            return 202401;
        }
    }

    private static final class Service extends Binder {
        private final Context mContext;
        // uid -> targeted verdict, valid for one targets generation: the
        // attestation path must not hit PackageManager on every chain read.
        private final SparseArray<Boolean> mTargetCache = new SparseArray<>();
        private long mTargetCacheVersion = -1;

        Service(Context context) {
            mContext = context;
        }

        private boolean isAdmin() {
            int uid = Binder.getCallingUid();
            if (uid == Process.SYSTEM_UID) return true;
            String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return false;
            for (String pkg : packages) {
                if (ADMIN_PACKAGE.equals(pkg)) return true;
            }
            return false;
        }

        private boolean isTargeted(int uid) {
            if (!enabled()) return false;
            if (KeyboxManager.allTargets()) return true;
            synchronized (mTargetCache) {
                long version = KeyboxManager.targetsVersion();
                if (version != mTargetCacheVersion) {
                    mTargetCache.clear();
                    mTargetCacheVersion = version;
                }
                Boolean cached = mTargetCache.get(uid);
                if (cached != null) return cached;
                boolean targeted = false;
                String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
                if (packages != null) {
                    for (String pkg : packages) {
                        if (KeyboxManager.isTargeted(pkg)) {
                            targeted = true;
                            break;
                        }
                    }
                }
                mTargetCache.put(uid, targeted);
                return targeted;
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                switch (code) {
                    case TRANSACT_STATUS:
                        return onStatus(reply);
                    case TRANSACT_PATCH:
                        return onPatch(data, reply);
                    case TRANSACT_INSTALL:
                        return onInstall(data, reply);
                    case TRANSACT_CLEAR:
                        return onClear(reply);
                    case TRANSACT_SET_TARGETS:
                        return onSetTargets(data, reply);
                    case TRANSACT_PUT_PIF:
                        return onPutPif(data, reply);
                    case TRANSACT_CLEAR_PIF:
                        return onClearPif(reply);
                    case TRANSACT_STORE:
                        return onStore(data, reply);
                    case TRANSACT_POOL:
                        return onPool(reply);
                    case TRANSACT_ROTATE:
                        return onRotate(reply);
                    case TRANSACT_MARK_REVOKED:
                        return onMarkRevoked(data, reply);
                    case TRANSACT_SET_AUTO_TARGET:
                        return onSetAutoTarget(data, reply);
                    case TRANSACT_ADD_ALL_INSTALLED:
                        return onAddAllInstalled(reply);
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            } catch (Throwable t) {
                Log.e(TAG, "transaction " + code + " failed", t);
                return false;
            }
        }

        private boolean onStatus(Parcel reply) {
            reply.writeInt(enabled() ? 1 : 0);
            reply.writeInt(KeyboxManager.installed() ? 1 : 0);
            reply.writeLong(KeyboxManager.generation());
            reply.writeString(KeyboxManager.activeSerial());
            reply.writeString(KeyboxManager.source());
            reply.writeString(KeyboxManager.version());
            reply.writeInt(KeyboxManager.allTargets() ? 1 : 0);
            reply.writeStringArray(KeyboxManager.targets());
            reply.writeInt(KeyboxManager.autoTarget() ? 1 : 0);
            List<KeyboxManager.PoolEntry> pool = KeyboxManager.pool();
            int spare = 0;
            for (KeyboxManager.PoolEntry entry : pool) {
                if (!entry.active) spare++;
            }
            reply.writeInt(spare);
            reply.writeInt(KeyboxManager.revokedCount());
            return true;
        }

        private boolean onPatch(Parcel data, Parcel reply) {
            int uid = data.readInt();
            String alias = data.readString();
            byte[] leaf = data.createByteArray();
            int count = data.readInt();
            List<byte[]> chain = new ArrayList<>(Math.max(count, 0));
            for (int i = 0; i < count; i++) chain.add(data.createByteArray());

            if (!enabled() || leaf == null || alias == null || !isTargeted(uid)) {
                reply.writeInt(RESULT_NONE);
                return true;
            }
            KeyboxManager.Store store = KeyboxManager.store();
            if (store == null) {
                reply.writeInt(RESULT_NONE);
                return true;
            }
            AttestationPatcher.Result result =
                    AttestationPatcher.patch(store, leaf, chain, patchLevel());
            if (result == null) {
                reply.writeInt(RESULT_NONE);
                return true;
            }
            reply.writeInt(RESULT_PATCHED);
            reply.writeByteArray(result.leaf);
            reply.writeInt(result.chain.size());
            for (byte[] cert : result.chain) reply.writeByteArray(cert);
            return true;
        }

        private boolean onInstall(Parcel data, Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            String xml = data.readString();
            String source = data.readString();
            String version = data.readString();
            String serial = xml == null ? null : KeyboxManager.install(xml, source, version);
            reply.writeInt(serial == null ? RESULT_ERROR : 0);
            reply.writeString(serial);
            return true;
        }

        private boolean onStore(Parcel data, Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            String xml = data.readString();
            String source = data.readString();
            String version = data.readString();
            String serial = xml == null ? null : KeyboxManager.store(xml, source, version);
            reply.writeInt(serial == null ? RESULT_ERROR : 0);
            reply.writeString(serial);
            return true;
        }

        private boolean onPool(Parcel reply) {
            List<KeyboxManager.PoolEntry> pool = KeyboxManager.pool();
            reply.writeInt(pool.size());
            for (KeyboxManager.PoolEntry entry : pool) {
                reply.writeString(entry.source);
                reply.writeString(entry.version);
                reply.writeString(entry.serial);
                reply.writeInt(entry.active ? 1 : 0);
                reply.writeInt(entry.revoked ? 1 : 0);
            }
            return true;
        }

        private boolean onRotate(Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            KeyboxManager.PoolEntry rotated = KeyboxManager.rotate();
            if (rotated == null) {
                reply.writeInt(RESULT_ERROR);
                reply.writeString(null);
                reply.writeString(null);
                reply.writeString(null);
                return true;
            }
            reply.writeInt(0);
            reply.writeString(rotated.serial);
            reply.writeString(rotated.source);
            reply.writeString(rotated.version);
            return true;
        }

        private boolean onMarkRevoked(Parcel data, Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            KeyboxManager.markRevoked(data.readString());
            reply.writeInt(0);
            return true;
        }

        private boolean onSetAutoTarget(Parcel data, Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            boolean enabled = data.readInt() != 0;
            KeyboxManager.setAutoTarget(enabled);
            if (enabled) {
                // Mirror Specter: enabling the feature also picks up what is
                // installed already.
                AutoTargetManager.sync(mContext);
            }
            reply.writeInt(0);
            return true;
        }

        private boolean onAddAllInstalled(Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            reply.writeInt(AutoTargetManager.addAllInstalled(mContext));
            return true;
        }

        private boolean onClear(Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            KeyboxManager.clearAll();
            reply.writeInt(0);
            return true;
        }

        private boolean onSetTargets(Parcel data, Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            String[] packages = data.createStringArray();
            boolean all = data.readInt() != 0;
            KeyboxManager.setTargets(packages, all);
            reply.writeInt(0);
            return true;
        }

        private boolean onPutPif(Parcel data, Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            String json = data.readString();
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.FETCHED_PIF, json);
            reply.writeInt(0);
            return true;
        }

        private boolean onClearPif(Parcel reply) {
            if (!isAdmin()) {
                reply.writeInt(RESULT_ERROR);
                return true;
            }
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.PIF_DATA, null);
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.FETCHED_PIF, null);
            reply.writeInt(0);
            return true;
        }
    }
}

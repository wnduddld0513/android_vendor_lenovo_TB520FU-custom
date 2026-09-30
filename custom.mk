#
# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Optional customizations for the Lenovo Yoga Tab Plus (TB520FU), pulled in by
# device/lenovo/TB520FU/custom_TB520FU.mk with inherit-product-if-exists. The
# device tree builds a plain PixelOS without this repository.
#

# Custom features app (Settings > System), TB520FUParts stays the device port)
PRODUCT_PACKAGES += \
    TB520FUCustomFeatures

# Game performance enforcement inside system_server (input/ extension point).
# tb520fu-input loads /system_ext/framework/tb520fu-input-custom.jar when it is
# present and lets it register with InputExtension.
PRODUCT_PACKAGES += \
    tb520fu-input-custom

# Galaxy Tab S11 Ultra identity for the Play Store: snapshots
# persist.sys.tb520fu.spoof_galaxy into sys.tb520fu.spoof_galaxy at boot.
# Needs patches/frameworks_base-0005 (PropImitationHooks).
PRODUCT_PACKAGES += \
    init.tb520fu.spoof.rc

# Play Integrity spoofing ("Custom features" > Integrity): snapshots
# persist.sys.tb520fu.integrity_* into sys.tb520fu.integrity_* at boot.
# Needs patches/frameworks_base-0006 (keybox attestation) and -0007 (PIF
# toggle); the keybox service lives in tb520fu-input-custom.jar.
PRODUCT_PACKAGES += \
    init.tb520fu.integrity.rc

# Lenovo Notes (stock ZuiNotes from the global ROM). Shipped as a plain file
# at /system_ext/etc/preinstall/ZuiNotes.apk, not as a system app:
# tb520fu-input-custom.jar installs it into /data once after the first boot
# (NotesPreinstall.java), so the user can uninstall it like any other app.
PRODUCT_PACKAGES += \
    ZuiNotes

# Default wallpaper (Pixel "Feathers" Porcelain live wallpaper)
PRODUCT_PACKAGES += \
    FeathersLiveWallpaper

# Overlays
PRODUCT_PACKAGES += \
    UpdaterResTB520FU \
    FrameworksResTB520FUCustom

# Updater (PixelOS OTA app; the device tree does not add it)
PRODUCT_PACKAGES += \
    Updater

PRODUCT_COPY_FILES += \
    vendor/custom/config/permissions/privapp-permissions-custom.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-custom.xml

PRODUCT_PRODUCT_PROPERTIES += \
    net.pixelos.build_type=unofficial

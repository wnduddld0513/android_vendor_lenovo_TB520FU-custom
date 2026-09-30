# TB520FU customizations (optional)

Maintainer additions on top of PixelOS for the Lenovo Yoga Tab Plus
(TB520FU), kept out of the device tree so a plain build stays clean and
upstream-able. The device tree uses this repository when it is synced and
builds a plain PixelOS for the device without it.

Contents:

| Path | What |
|---|---|
| `TB520FUCustomFeatures/` | "Custom features" app (Settings > System): game performance and the Galaxy Tab S11 Ultra identity for the Play Store |
| `input/` | `tb520fu-input-custom.jar`, the game performance enforcement loaded into system_server by `tb520fu-input` |
| `ZuiNotes/` | Lenovo Notes (stock `ZuiNotes.apk` from the global ROM) |
| `FeathersLiveWallpaper/` | Pixel "Feathers" Porcelain live wallpaper, the default wallpaper |
| `overlay/FrameworksResTB520FUCustom/` | defaults for the notes role and the wallpaper |
| `overlay/UpdaterResTB520FU/` | the updater's SourceForge folder and hidden certified-props item |
| `init/` | `init.tb520fu.spoof.rc`, the boot-time spoof snapshot |
| `sepolicy/vendor/` | the cpufreq/kgsl rules of the game performance controller |
| `patches/` | PixelOS source patches, applied by `patches/apply.sh` |
| `tools/custom_strings.py` | generates the app's `res/values*/strings.xml` |
| `tools/ota_json.py` | writes the updater description of a build |

## How it hooks into the device tree

Three hooks, all no-ops when this repository is not synced:

- `device/lenovo/TB520FU/custom_TB520FU.mk` inherits `custom.mk` with
  `inherit-product-if-exists`, so the packages above exist only here.
- `device/lenovo/TB520FU/BoardConfig.mk` includes `BoardConfigCustom.mk` with
  `-include` (the vendor sepolicy directory).
- `device/lenovo/TB520FU/patches/apply.sh` runs `patches/apply.sh` here at the
  end, and reverts its patches automatically when this repository is removed
  (the list is kept in `.tb520fu-custom-applied/` at the source root).

The device tree only calls into this repository through the
`com.tb520fu.input.InputExtension` interface (`input/` in the device tree):
`InputCore` loads `/system_ext/framework/tb520fu-input-custom.jar` with a
`PathClassLoader` when the file exists, so `tb520fu-input` keeps working on a
build without it.

```bash
# in the source root; after every repo sync
bash device/lenovo/TB520FU/patches/apply.sh
```

## OTA publishing

Each build is published in two SourceForge folders:

| Folder | Files |
|---|---|
| `seventeen/<date>/` | full packages `PixelOS_TB520FU-<version>-<date>[-ROW].zip` (recovery / TWRP / full OTA) and the LTBox archives |
| `seventeen/OTA/<date>/` | updater descriptions `PixelOS_TB520FU-<version>-<date>[-ROW].json` and the incremental packages |

The updater reads the RSS feed of `seventeen/OTA`, takes the newest build
folder with a description of the running variant (PRC / ROW, from the running
dtb) and offers it when it is newer than the running build. It downloads the
incremental package from `seventeen/OTA/<date>/` when update_engine accepts its
payload metadata for the running build, otherwise the full package from
`seventeen/<date>/`; packages from anywhere else are refused. The package
SHA-256 is checked, update_engine checks the AOSP OTA signature.

An incremental OTA is built from the **target files of both builds**, so keep
the target files of every published build (the full OTA ZIP cannot replace
them). For each region separately:

```bash
ota_from_target_files -k build/make/target/product/security/testkey \
    -i BASE_TARGET_FILES TARGET_TARGET_FILES \
    PixelOS_TB520FU-17.0-TARGET_DATE-incremental-BASE_DATE.zip
python3 vendor/lenovo/TB520FU-custom/tools/ota_json.py \
    --base-full BASE_FULL.zip \
    --incremental PixelOS_TB520FU-17.0-TARGET_DATE-incremental-BASE_DATE.zip \
    --out-dir OTA_FOLDER TARGET_FULL.zip
```

`--base-build DIR` (`DIR/target_files`) can replace `--base-full`. ota_json.py
rejects a delta whose source/target metadata or streaming ranges do not match,
and writes the package URLs for the folder layout above.

# Play Integrity Fix (no root, no modules)

An optional firmware level Play Integrity Fix, exposed in the "Custom Tweaks"
app (Settings > System). No Magisk, KernelSU, Zygisk, root or `/data/adb` files
are involved.

## The switch

"Custom Tweaks" > "Play Store" > **Play Integrity Fix** arms the whole
feature; the options and the status live on its own screen ("Play Integrity Fix
settings"). An unset switch means off, so a fresh install and a factory reset
start with it disabled; the app writes an explicit 0 or 1 once the switch is
used.

The first change needs one restart (the app offers it); keybox and fingerprint
updates apply live afterwards.

## The management screen

* **Keybox Manager** - state (installed, source, serial), the spare pool,
  automatic renewal, "Replace automatically when revoked", "Renew now" and
  "Delete keybox".
* **TEE Simulator** - state, the keybox in use, "All apps", "Add new apps
  automatically", "Add all installed apps" and the target list.
* **PIF** - pif.json status, the security patch and the fingerprint it
  carries, "Fetch now" and "Delete pif.json".
* **Automatic checks** - the check interval and the unlock cooldown. Both are
  thirty minutes by default and can be set from fifteen minutes up to six
  hours; every unlock restarts the countdown and a check runs once it has
  elapsed.

Tapping the title of a switch row opens its screen; the switch itself (or any
other part of the row) toggles the feature.

## Turning it off

With the fix off the framework hooks do nothing, the feature's services serve
nothing and no job is running. The restart after it was turned off also
**removes its data** - the keybox and its spares, the target list and the
fingerprint data - so nothing of a disabled feature stays on disk. "Delete
keybox" removes the keybox and the whole spare pool on demand.

## Installer

"Report the Play Store as the installer" (off by default, applies immediately):
apps that are installed outside the Play Store then report it as their
installer, so apps that insist on a Play install keep working. Only the
installer name is replaced, so updates through the Play Store replacement keep
working.

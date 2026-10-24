# Patches

Reproduce this fork's changes on top of pristine upstream Fermata (2.0.2). Already applied to the
repo — kept for rollback / re-derivation. The *why* is in `../CHANGELOG.md`; build commands in
`../BUILD.md`.

Apply in order with `git apply`:

1. `0001-core-slimming-remove-non-core-features.patch` — remove non-core addons/engines and the
   SMB/SFTP/GDrive network VFS providers (excluded in `settings.gradle` + core wiring removed).
2. `0002-ui-internet-toggle-and-favorites-nav.patch` — add the Internet-access toggle; drop Favorites
   from the default bottom nav.
3. `0003-mirror-donate-update-removal.patch` — remove the "Fermata Mirror" Android-Auto launcher entry
   (`MirrorService`), remove the "Coffee for developer" donate menu item, and disable the startup
   GitHub update check (+ remove its Settings row).

On-demand (apply only if publishing to Google Play):

- `play-publishable-disable-carappservice.patch` — **required to make the app Play-publishable.**
  Comments out the androidx.car.app CarAppService entry point (ACCESS_SURFACE/NAVIGATION_TEMPLATES
  permissions, the `android.hardware.type.automotive` feature, and the `MirrorServiceFS`
  CarAppService/NAVIGATION filter), because Play rejects an app that exposes both a
  `MediaBrowserService` and a `CarAppService`. Trade-off: disables the `MirrorServiceFS`
  ("FS Fermata Mirror") car-app surface. Not needed for direct/universal-APK sideload. If applied
  alongside patch 3, apply patch 3 after it (both edit `fermata/src/auto/AndroidManifest.xml`).

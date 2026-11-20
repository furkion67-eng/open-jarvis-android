# JARVIS Device Access — shared storage and capability status

## Shared storage

- Access is limited to document/tree URIs explicitly selected by the user through Android's system document picker.
- The picker result's actual read/write flags are captured and only those returned grants are passed to takePersistableUriPermission.
- StorageUriGrantRegistry stores the selected URI and checks ContentResolver.persistedUriPermissions again after process recreation. A revoked/missing grant is removed from the local registry and is never treated as active.
- The Compose panel restores the last still-readable tree, lists/searches the current folder, opens files with an installed handler, shares through Android's chooser, creates folders and UTF-8 text files, renames items, and exposes copy/move after an explicit destination-folder selection and exact source/target confirmation. Deletion requires typing the exact unchanged filename.
- SafStorageGateway checks every tree/document URI against a currently persisted read grant for the same provider authority and tree document ID; write operations additionally require a persisted write grant. Read/write/rename/copy/move/delete cannot be invoked against arbitrary content URIs outside a granted tree. Provider failures are surfaced; they are not reported as success.
- The gateway cannot enumerate all storage or read another app's private sandbox.

## Capability status

DeviceCapabilityStatusPanel is a read-only status view for Contacts, SMS, camera, microphone, notifications, media, user-selected storage, overlay, accessibility and battery/background limits. It distinguishes never-requested, denied and previously-granted-but-now-revoked runtime permissions using local permission history. It does not request permissions automatically.

Accessibility is reported unavailable unless an enabled service from this package is actually present. Opening Android settings is a user action; JARVIS cannot silently grant overlay, accessibility or battery exemptions.

## Integration still required

- Wire StorageAccessPanel and DeviceCapabilityStatusPanel into the host app navigation.
- Copy and move ask the user to choose a destination with ACTION_OPEN_DOCUMENT_TREE, require a live persisted write grant, and show the exact source, destination name and destination URI in a second confirmation. Rename rechecks the original display name before applying the previewed new name. A mutation lock prevents duplicate taps while a provider operation is in progress; unsupported provider operations remain visible errors.
- The repository snapshot has no root Gradle wrapper/build scripts or host app manifest, so app wiring, compilation, Android instrumentation and physical TECNO acceptance cannot be claimed from this source-only feature tree.
- Use SAF for user-selected general files. Use MediaStore and Android 13+ granular media permissions only when a feature genuinely needs library-wide media access. Never request MANAGE_EXTERNAL_STORAGE as a shortcut.

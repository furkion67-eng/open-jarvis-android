# JARVIS Device Access — shared storage

This first slice implements shared-storage operations through Android's Storage Access Framework (SAF).

## Security model

- JARVIS receives access only to files/folders the user explicitly selects in Android's system picker.
- The picker-returned read/write flags are persisted with `takePersistableUriPermission`; no permission is fabricated.
- The gateway cannot read another app's private sandbox and does not enumerate all device storage.
- Providers may reject copy/move/rename or expose read-only documents. Such failures are surfaced instead of being reported as success.
- Deletion requires a confirmation object bound to the exact URI and exact current display name. The UI must show the target and ask the user to type its name before calling `confirmDeletion` and `delete`.
- URI grants can be revoked by the user in Android settings; every operation may therefore fail with a security/provider error and the UI must offer re-selection.

## Implemented API

`SafStorageGateway` supports:
- Persisting a picker grant.
- Listing children of a granted tree.
- Creating files and folders.
- Reading and writing bytes.
- Renaming, copying and moving where the selected document provider supports those operations.
- Confirmed deletion bound to the exact selected URI.

## Integration still required

The main app must launch `ACTION_OPEN_DOCUMENT_TREE` / `ACTION_OPEN_DOCUMENT`, pass the returned URI and result flags to this gateway, retain selected URI references in app storage, and expose a Turkish capability screen showing granted/revoked access. The app's manifest, Gradle module wiring, UI, and device tests are not present in the currently accessible repository source tree, so this commit does not claim a working APK or complete phone control.

Android 13+ media permissions and MediaStore should be added only for use cases that need broad media-library access; SAF remains the safer general file/folder path. Camera, microphone, contacts, SMS, notifications, app launching and accessibility are separate permission-gated integrations.

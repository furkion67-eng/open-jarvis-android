# JARVIS Device Actions — Contacts and SMS

This isolated feature slice adds contact-first recipient resolution and a confirmation-gated SMS transport.

## Contact resolution

- Uses the Android ContactsContract phone-number provider; only contacts with phone numbers are candidates.
- AndroidContactsResolver checks READ_CONTACTS and never launches a permission prompt itself. The Compose caller requests runtime permission through ActivityResultContracts.RequestPermission.
- Names use Turkish-aware lowercasing, Unicode mark removal and whitespace/punctuation folding.
- Exact, prefix, token and contains matches are ranked. More than one best contact returns ChooseContact; one contact with multiple numbers returns ChooseNumber; one contact with one number returns Unique.
- A unique match is selected directly. SmsComposePanel does not display a manual phone-number field. No-match and ambiguous results are explicit.

## SMS safety contract

- SmsComposePanel shows the resolved recipient name, exact phone number and exact message in an AlertDialog.
- The user must tap “Onayla ve SMS gönder”. A permission grant never triggers sending automatically; after granting SEND_SMS, the user must reopen the preview and confirm.
- SmsConfirmationGate issues a one-time confirmation token. ConfirmedSmsDispatcher consumes it once and checks permission again immediately before transport.
- AndroidSmsSender uses SmsManager only when SEND_SMS is granted and the device exposes telephony messaging.
- Multipart messages are tracked per part. The outcome is Sent only after every SMS_SENT callback reports Activity.RESULT_OK. A failure becomes Failed; a missing callback becomes Unknown, never Sent.
- Sent means Android reported successful submission to the telephony stack. It does not mean the recipient's device received or read the message; no delivery claim is made.
- If direct SMS permission is denied or unavailable, the user may explicitly open an ACTION_SENDTO draft in the default SMS app. That draft is not sent and JARVIS does not mark it Sent.
- No real SMS is sent by unit or CI tests. Tests use pure policy/state contracts and fake transports.

## Android permission and distribution limits

The feature manifest declares READ_CONTACTS and SEND_SMS; the host app must merge this manifest and request each dangerous permission only in response to the relevant user action. If permission is denied/revoked, the operation stops without claiming success.

SEND_SMS is a restricted/sensitive permission for Google Play distribution and may require an eligible core use case and policy approval. If distribution policy or the device does not allow direct sending, use an explicit ACTION_SENDTO hand-off to the user's SMS app; that hand-off is not evidence that a message was sent.

## Integration still required

- Add this feature source set to the real Android app's Gradle settings and merge app/src/main/AndroidManifest.xml.
- Wire SmsComposePanel into a user-visible assistant screen and connect onSmsOutcome to the durable task journal. Persist SENT only for SmsSendOutcome.Sent; preserve Unknown when the callback is absent or the process dies.
- The repository snapshot currently has no root Gradle wrapper/build scripts or host app manifest. Therefore this source slice cannot yet be compiled as an Android app or instrumented on Android 13/14 in this repository state.
- Physical TECNO acceptance, real contact-provider behavior and real SMS delivery are not claimed. Never use a real recipient or real SMS in CI.

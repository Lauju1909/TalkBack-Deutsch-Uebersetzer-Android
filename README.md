# TalkBack Deutsch-Übersetzer (Android)

Eine vollwertige, barrierefreie Android-Begleiter-App für **TalkBack** und sehbehinderte Nutzer. 
Sobald die Geste (oder der Schnellzugriff) ausgelöst wird, erfasst die App alle Texte fremder Anwendungen oder Webseiten, übersetzt sie automatisch ins Deutsche und liest sie sofort über die deutsche Android-Sprachausgabe (TTS) vor.

## Kernfunktionen

1. **2-Finger-Geste auf dem Display**:
   - Erkennt Zwei-Finger-Gesten (`GESTURE_2_FINGER_SINGLE_TAP`, `GESTURE_2_FINGER_DOUBLE_TAP`) in Android 11+ (API 30+) über den Barrierefreiheitsdienst.
2. **TalkBack-Kompatibilität**:
   - Läuft parallel zu TalkBack als offizieller `AccessibilityService`.
   - Optionaler **TalkBack Live-Fokus-Modus**: Sobald TalkBack ein fremdsprachiges Element fokussiert, wird dieses in Echtzeit übersetzt und auf Deutsch angesagt.
3. **Schwebender Barrierefrei-Button (Floating Bubble)**:
   - Ein optionaler, großer, kontrastreicher Schwebeschalter, der über allen Apps liegt und auch mit einfachen Berührungen oder 2 Fingern ausgelöst werden kann.
4. **On-Device Übersetzung (Privatsphäre & Offline)**:
   - Nutzt Google ML Kit On-Device Translation. Keine Internetverbindung für die Übersetzung erforderlich, keine API-Kosten, höchste Geschwindigkeit.
5. **Akustisches & Haptisches Feedback (Earcons & Vibration)**:
   - Signalisiert Start des Scans, erfolgreiche Übersetzung und Statuswechsel für blinde Nutzer per Ton und Vibration.
6. **Einstellbare Sprachausgabe**:
   - Regelbare Sprechgeschwindigkeit (z. B. 0.5x bis 2.0x) und Tonhöhe.

## Aktivierung auf dem Smartphone

1. App installieren (`adb install app-debug.apk` oder direkt aus Android Studio).
2. App öffnen und auf **„Bedienungshilfe aktivieren“** tippen.
3. In den Android-Einstellungen den Dienst **„TalkBack Deutsch-Übersetzer“** einschalten.
4. Falls gewünscht, die **Overlay-Berechtigung** für den schwebenden Button erteilen.
5. In beliebiger englischer oder fremdsprachiger App mit 2 Fingern tippen – der Inhalt wird sofort auf Deutsch vorgelesen!

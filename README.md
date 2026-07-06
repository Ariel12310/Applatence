# Delayed Voice Monitor

Application Android Flutter qui capture le micro, ajoute une latence audio artificielle réglable et renvoie le son vers la sortie audio du téléphone, d'un casque filaire, USB ou Bluetooth.

## Fonctionnalités incluses

- Interface Flutter Material 3 en dark mode.
- Gros boutons **START** / **STOP**.
- Latence réglable de **0 ms à 5000 ms** par pas de 10 ms.
- Niveau micro en temps réel.
- Sélection de sortie audio via `AudioTrack.setPreferredDevice` sur Android 6+.
- Service foreground Android avec notification permanente.
- Permissions micro, Bluetooth et notification.
- Traitement audio natif Kotlin : `AudioRecord` + tampon circulaire + `AudioTrack`.
- Reprise après rotation/changement d'écran : l'audio continue dans le service.
- Builds automatisés GitHub Actions : debug APK, release APK, AAB.

## Important sur la latence Bluetooth

L'application ajoute la latence demandée dans le logiciel. Les casques Bluetooth ajoutent aussi leur propre latence matérielle/codecs, souvent 100 à 300 ms ou plus. Donc si tu règles 500 ms sur un casque Bluetooth, la latence entendue peut être environ 500 ms + latence Bluetooth. Pour une précision maximale, utilise un casque filaire/USB.

## Structure

```text
lib/main.dart                                      UI Flutter + MethodChannel
android/app/src/main/kotlin/.../MainActivity.kt   pont Flutter ↔ Android
android/app/src/main/kotlin/.../DelayedAudioService.kt service foreground
android/app/src/main/kotlin/.../AudioDelayEngine.kt moteur audio temps réel
.github/workflows/build-android.yml               compilation cloud APK/AAB
```

## Compilation avec PC ou cloud

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
flutter build apk --release
flutter build appbundle --release
```

## Installation sans PC via GitHub Actions

Voir `docs/INSTALLATION_ANDROID_SEUL.md`.

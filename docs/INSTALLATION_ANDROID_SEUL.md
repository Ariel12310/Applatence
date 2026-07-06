# Installer l'application avec uniquement un téléphone Android

Flutter complet directement sur Android est possible seulement avec des bricolages Termux/proot lourds et fragiles. La méthode fiable sans PC est donc :

**Téléphone Android → GitHub en navigateur → GitHub Actions compile dans le cloud → téléchargement APK sur le téléphone → installation.**

## Étape 1 — créer un compte GitHub

1. Ouvre Chrome sur ton téléphone.
2. Va sur https://github.com
3. Connecte-toi ou crée un compte.

## Étape 2 — créer un dépôt

1. Appuie sur `+` puis `New repository`.
2. Nom : `delayed-voice-monitor`.
3. Choisis `Private` ou `Public`.
4. Coche `Add a README file` si GitHub le demande.
5. Crée le dépôt.

## Étape 3 — envoyer les fichiers du projet

Option la plus simple depuis téléphone :

1. Télécharge le ZIP fourni par Arena.
2. Décompresse-le avec l'application `Files`, `ZArchiver` ou `RAR`.
3. Dans GitHub mobile, ouvre ton dépôt.
4. Appuie sur `Add file` → `Upload files`.
5. Envoie tout le contenu du dossier `delayed_voice_monitor`.
6. Vérifie que `.github/workflows/build-android.yml` est bien présent.
7. Appuie sur `Commit changes`.

Si GitHub mobile refuse d'envoyer beaucoup de fichiers d'un coup, envoie d'abord les dossiers principaux : `lib`, `android`, `.github`, `test`, puis les fichiers à la racine (`pubspec.yaml`, `README.md`, `analysis_options.yaml`).

## Étape 4 — lancer la compilation

1. Dans ton dépôt GitHub, ouvre l'onglet `Actions`.
2. Choisis `Build Android APK and AAB`.
3. Appuie sur `Run workflow`.
4. Attends la fin : environ 5 à 15 minutes.

## Étape 5 — télécharger l'APK

1. Quand l'action est verte, ouvre le run terminé.
2. Descends jusqu'à `Artifacts`.
3. Télécharge `delayed-voice-monitor-android-builds`.
4. Décompresse le ZIP téléchargé.
5. Installe `app-debug.apk` ou `app-release.apk`.

`app-debug.apk` est le plus sûr pour l'installation directe. `app-release.apk` est aussi installable ici car il est signé avec la clé debug du build Flutter, volontairement, pour éviter de gérer une clé privée depuis un téléphone.

## Étape 6 — autoriser l'installation APK

Android peut afficher : `Installation depuis cette source non autorisée`.

1. Appuie sur `Paramètres`.
2. Autorise Chrome / Files à installer des applications inconnues.
3. Reviens au fichier APK.
4. Installe.

## Utilisation

1. Branche ou connecte ton casque avant de démarrer.
2. Ouvre l'application.
3. Autorise le micro, Bluetooth si demandé, et notification.
4. Sélectionne la sortie audio.
5. Règle la latence, par exemple 500 ms.
6. Appuie sur **START**.
7. Appuie sur **STOP** pour arrêter le service.

## Conseils anti-larsen

- Utilise un casque, pas le haut-parleur du téléphone.
- Commence avec volume faible.
- Si tu utilises le haut-parleur, le micro peut reprendre le son et créer un écho/larsen.

## Limites réalistes

- Android ne permet pas toujours de forcer absolument toutes les routes audio sur tous les modèles.
- Le Bluetooth ajoute une latence matérielle impossible à supprimer.
- Le traitement est temps réel, mais la précision finale dépend du téléphone, du casque, du codec Bluetooth et de la charge CPU.

# OpenJarvis — Assistant IA Personnel pour Android & Périphériques Locaux

<div align="center">
  <img alt="OpenJarvis" src="assets/OpenJarvis_Horizontal_Logo.png" width="400">
  <p><strong>Intelligence Artificielle Personnelle, Embarquée & Hybride sur Android</strong></p>
  <p>
    <img src="https://img.shields.io/badge/Android-API%2026%2B%20(Android%208.0%20--%2015)-brightgreen?logo=android" alt="Android Support">
    <img src="https://img.shields.io/badge/Architecture-Kotlin%20%7C%20Compose%20%7C%20React%20%7C%20Vite-blue" alt="Architecture">
    <img src="https://img.shields.io/badge/Storage-Room%20FTS5%20Encrypted-purple" alt="FTS5 Memory">
    <img src="https://img.shields.io/badge/Voice-Fast%20VAD%20%2B%20TTS%20Stream-cyan" alt="Voice Engine">
    <img src="https://img.shields.io/badge/License-Apache%202.0-green" alt="License">
  </p>
</div>

---

## Sommaire

1. [Vue d'Ensemble & Philosophie](#1-vue-densemble--philosophie)
2. [Architecture Complète](#2-architecture-complète)
3. [Sécurité & Confidentialité](#3-sécurité--confidentialité)
4. [Moteurs Embarqués & Capacités](#4-moteurs-embarqués--capacités)
   - [Mémoire Personnelle FTS5 & RAG Local](#mémoire-personnelle-fts5--rag-local)
   - [Moteur Vocal Temps Réel & VAD](#moteur-vocal-temps-réel--vad)
   - [Vision Multimodale Hybride & OCR](#vision-multimodale-hybride--ocr)
   - [Orchestration Multi-Agents & Outils](#orchestration-multi-agents--outils)
   - [Passerelle Android & Contrôle Système](#passerelle-android--contrôle-système)
5. [Résilience Hors-Ligne (Mode Offline)](#5-résilience-hors-ligne-mode-offline)
6. [Compilation & Déploiement Android (APK / AAB)](#6-compilation--déploiement-android-apk--aab)
7. [Configuration & Clés API](#7-configuration--clés-api)
8. [Permissions Android](#8-permissions-android)
9. [Dépannage & FAQ](#9-dépannage--faq)
10. [Licence & Références](#10-licence--références)

---

## 1. Vue d'Ensemble & Philosophie

**OpenJarvis Android** est un assistant personnel intelligent conçu pour fonctionner en **mode local par défaut (On-Device First)**, complété de manière transparente par des modèles multimodaux dans le Cloud lorsque la puissance de calcul le requiert ou que des requêtes complexes l'exigent.

Contrairement aux assistants conventionnels qui transmettent chaque interaction à des serveurs tiers :
- **Confidentialité Totale** : Vos souvenirs, vos notes et vos données personnelles sont indexés localement dans une base chiffrée **Room SQLite FTS5**.
- **Indépendance Réseau** : En l'absence de connexion Internet, JARVIS continue d'exécuter vos calculs, gérer vos rappels, consulter vos souvenirs et piloter vos applications Android.
- **Efficacité Énergétique** : Optimisé pour une consommation minimale de RAM, de CPU et de batterie (*Intelligence Per Watt*).

---

## 2. Architecture Complète

L'application est structurée en 3 couches complémentaires :

```
┌─────────────────────────────────────────────────────────────┐
│                 Interface JARVIS HUD (React 19)             │
│  - Cœur Neuronal Holographique (Arc Reactor)                │
│  - Télémétrie en temps réel (FPS, RAM, Latence réseau)       │
│  - Visualiseur audio spectral & Décomposition d'agents      │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│           Orchestrateur Hybride (Serveur Node / tsx)         │
│  - API REST & Flux Streaming SSE (/v1/chat/completions)     │
│  - Détection automatique d'outils (Calculator, Web Search)  │
│  - Moteur de Vision Multimodale (OCR + Gemini 3.7 Flash)    │
│  - Synthèse Vocale & Transcription (TTS + Gemini Voice)     │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│             Couche Native Android (Kotlin / Room)           │
│  - AndroidBridge : Gestion des Intents (WhatsApp, Maps, etc)│
│  - Chiffrement Android Keystore & EncryptedSharedPreferences│
│  - Base de données Room FTS5 (Recherche sémantique locale)  │
│  - Service d'arrière-plan avec réveil vocal (Wake Word)     │
└─────────────────────────────────────────────────────────────┘
```

---

## 2 bis. Nouveautés : mémoire durable, appairage, sauvegarde, briefing

- **Données persistantes (SQLite)** : mémoire, routines, macros vocales, automatisations, rappels, domotique et réglages sont restaurés au redémarrage (`data/openjarvis.sqlite`, droits `600`, chemin modifiable via `OPENJARVIS_DB`). Nécessite Node ≥ 22.5 (module `node:sqlite` intégré) ; sinon le serveur fonctionne en mémoire et l'indique.
- **Appairage d'un téléphone** : *Paramètres → Téléphones associés* affiche un code à usage unique (et un QR code) valable 5 minutes. Le téléphone reçoit **sa propre clé** (jamais la clé maître), révocable à tout moment. Pour un téléphone sur le réseau local : `HOST=0.0.0.0` + `OPENJARVIS_API_KEY`.
- **Sauvegarde chiffrée** : export/import de vos données en un fichier AES-256-GCM protégé par une phrase secrète (les clés des appareils n'en font jamais partie).
- **Briefing du matin réel** : météo en direct (si une clé météo est configurée), rappels, alertes et routines vocales ; plus aucune donnée inventée.
- **Design** : palette « graphite + arc », typographie Instrument Sans / Instrument Serif pour la voix de l'assistant, mouvement réduit respecté, focus clavier visible.

---

## 3. Sécurité & Confidentialité

Modèle de sécurité du serveur (implémenté dans `src/server/security.ts`, testé dans `tests/server/`) :

- **Authentification globale** : toutes les routes `/api/*` et `/v1/*` exigent la clé `OPENJARVIS_API_KEY` (`Authorization: Bearer <clé>` ou `X-API-Key`). Seules `/health`, `/api/auth/status` et `/api/android/verify-connection` sont publiques. Les requêtes venant **directement de la machine locale** (loopback, sans reverse proxy) sont acceptées sans clé pour le développement ; mettez `OPENJARVIS_REQUIRE_AUTH=true` pour l'interdire.
- **CORS en liste blanche** : seules les origines locales (localhost, WebView Android/Capacitor/Tauri) et celles listées dans `ALLOWED_ORIGINS` sont acceptées. Plus aucune origine n'est « reflétée ».
- **Protection DNS-rebinding** : le header `Host` des requêtes locales est vérifié (`ALLOWED_HOSTS` pour ajouter des noms).
- **Exposition réseau minimale** : le serveur écoute par défaut sur `127.0.0.1`. Pour l'accès depuis un téléphone sur le réseau local, définissez `HOST=0.0.0.0` **et** une clé `OPENJARVIS_API_KEY` d'au moins 16 caractères (idéalement derrière HTTPS).
- **Limites de débit** : 600 req/min par IP, 30 req/min sur les routes IA/voix/vision, 30 échecs d'authentification / 10 min, 10 échecs de code d'identité / 10 min.
- **Taille des requêtes** : 2 Mo par défaut ; 35 Mo uniquement pour la vision, la transcription et le chat multimodal.
- **Code d'identité (PIN / phrase)** : aucun code par défaut. Il est choisi par l'utilisateur (≥ 4 caractères), stocké **haché (scrypt + sel)** côté serveur, jamais renvoyé par l'API, comparé en temps constant, et nécessite l'ancien code pour être modifié.
- **Aucune clé secrète dans le dépôt** : les clés passent par `.env` (voir `.env.example`) et ne sont jamais exposées au navigateur.
- **Stockage Android chiffré** : `AndroidX Security Crypto` (AES-256 GCM), `allowBackup=false`, `network_security_config.xml` interdisant le trafic en clair, build release avec R8.
- **Garde-fous d'action** : confirmation explicite pour les actions sensibles (jetons de confirmation, journal d'audit).

> ⚠️ Cette liste décrit des protections *applicatives*. Aucun audit de sécurité externe n'a été réalisé : ne l'exposez pas sur Internet sans HTTPS ni revue indépendante. Voir `docs/SECURITY.md`.

---

## 4. Moteurs Embarqués & Capacités

### Mémoire Personnelle FTS5 & RAG Local
- **Indexation Vectorielle & Plein Texte** : Table virtuelle SQLite FTS5 permettant des requêtes sémantiques instantanées (< 5 ms).
- **Catégorisation des Faits** : Découpage intelligent par préférences, relations, santé, objectifs et notes techniques.
- **Export & Sauvegarde** : Sauvegarde locale exportable en JSON chiffré sans dépendance externe.

### Moteur Vocal Temps Réel & VAD
- **Détection d'Activité Vocale (VAD)** : Traitement continu du flux audio avec calcul de décibels et seuil dynamique.
- **Commandes de Réveil "Hey Jarvis"** : Prise en charge native du mot de réveil avec déclenchement haptique.
- **Égaliseur Spectral** : Visualiseur de fréquences audio dynamique à 60 fps (ou 30 fps en mode Éco).

### Vision Multimodale Hybride & OCR
- **Capture Caméra Instantanée** : Sélecteur photo haute résolution avec bascule capteur avant / arrière.
- **Tâches Spécialisées** :
  1. *Analyse Générale de Scène* : Détection de contexte et relations spatiales.
  2. *OCR & Extraction de Documents* : Reconnaissance textuelle de factures, contrats et tableaux.
  3. *Inventaire d'Objets* : Comptage et description précise.
- **Synthèse Vocale des Analyses** : Résumé oral concis produit automatiquement pour écoute mains-libres.

### Orchestration Multi-Agents & Outils
- **Décomposition en 5 Étapes** : Comprendre $\to$ Rechercher $\to$ Analyser $\to$ Comparer $\to$ Recommander.
- **Outils Connectés Intégrés** :
  - `calculator` : Évaluateur d'expressions arithmétiques et pourcentages.
  - `web_search` : Recherche d'actualités et comparatifs de prix en temps réel.
  - `reminder_scheduler` : Planification de rappels compatibles avec `AlarmManager` Android.
  - `vision_analyzer` : Analyse neuronale d'images.
  - `android_intent` : Lancement d'applications tierces.

### Passerelle Android & Contrôle Système
- **Lancement d'Applications Officielles** : WhatsApp, YouTube, Google Maps, Spotify, Gmail, Calendrier, Horloge, Téléphone, etc.
- **Retour Haptique** : Vibrations distinctes selon l'état (`light`, `medium`, `heavy`).
- **Gestion des Permissions à la Demande** : Modales explicatives avant toute demande de permission sensible (Microphone, Caméra, Notifications, Localisation).

---

## 5. Résilience Hors-Ligne (Mode Offline)

Lorsque l'appareil perd sa connexion réseau :
1. **Basculement Automatique** : L'interface active le badge `Mode Local / Hors-ligne` et ajuste les indicateurs de latence.
2. **Fonctions Maintenues** :
   - Consultation et recherche dans la mémoire FTS5.
   - Exécution des calculs mathématiques et conversions.
   - Programmation d'alarmes et rappels locaux.
   - Lancement d'applications et fonctionnalités matérielles.
   - Reconnaissance et synthèse vocale Web Speech intégrées.
3. **Reconnexion Transparente** : Dès que l'accès réseau est restauré, JARVIS réactive automatiquement la recherche web, les agents distribués et les modèles cloud multi-modaux.

---

## 6. Compilation & Déploiement Android (APK / AAB)

Le projet intègre une configuration Gradle moderne pour compiler l'application native Android.

### Prérequis
- **JDK 17** ou supérieur.
- **Android SDK** (API 35, Build-Tools 35.0.0).
- **Node.js 20+** pour le packaging des assets web frontend.

### 1. Génération de l'APK Debug (Test & Émulateur)
```bash
# Compiler les assets frontend
npm run build

# Compiler l'APK Debug Android
cd android
./gradlew assembleDebug
```
*Le fichier APK généré se trouve dans : `android/app/build/outputs/apk/debug/app-debug.apk`.*

### 2. Génération de l'APK Release (Installation Directe)
```bash
cd android
./gradlew assembleRelease
```
*Le fichier APK optimisé par ProGuard/R8 se trouve dans : `android/app/build/outputs/apk/release/app-release-unsigned.apk`.*

### 3. Génération du Bundle Android App Bundle (AAB pour Google Play Store)
```bash
cd android
./gradlew bundleRelease
```
*Le bundle AAB optimisé se trouve dans : `android/app/build/outputs/bundle/release/app-release.aab`.*

### Signature de l'application (Release Keystore)
```bash
# Générer une clé de signature
keytool -genkey -v -keystore openjarvis-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias jarvis-key

# Signer l'APK
zipalign -v -p 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks openjarvis-release-key.jks --out openjarvis-release-signed.apk app-release-aligned.apk
```

---

## 7. Configuration & Clés API

Dans l’application Android native, ouvrez *Paramètres → Clés d’API & Sécurité* pour configurer l’URL du serveur LAN et une clé maître ou un jeton d’appareil appairé. Le bouton *Vérifier la connexion au serveur* indique si l’hôte est joignable et, pour OpenJarvis, si le jeton est accepté. La clé est stockée dans le coffre chiffré Android et envoyée comme jeton Bearer aux requêtes de chat. Les serveurs Ollama/vLLM compatibles OpenAI sont également détectés, mais ne permettent pas toujours de vérifier la clé. Pour un appareil physique, utilisez HTTPS ; l’exception HTTP est limitée à quelques hôtes de développement.

### Variables d'Environnement
Créez un fichier `.env` à la racine :

```env
# Clé Gemini pour les fonctionnalités multimodales et TTS (optionnelle)
GEMINI_API_KEY=votre_cle_gemini_ici

# URL personnalisée du serveur d'inférence (si déporté)
VITE_API_URL=

# Destination of the optional Expo WebView wrapper (App.expo.js).
# Set it to your own OpenJarvis server; prefer HTTPS. Never put API keys here.
EXPO_PUBLIC_APP_URL=https://openjarvis.example.com

# --- Sécurité du serveur ---
# Clé d'accès à l'API (>= 16 caractères). Obligatoire pour tout accès non local (Android, LAN).
# Génération : openssl rand -hex 32
OPENJARVIS_API_KEY=
# Interface d'écoute (défaut 127.0.0.1) et port (défaut 3000)
HOST=127.0.0.1
PORT=3000
# Origines web supplémentaires autorisées (CORS), séparées par des virgules
ALLOWED_ORIGINS=
# Noms d'hôte supplémentaires autorisés pour les requêtes locales (anti DNS-rebinding)
ALLOWED_HOSTS=
# true = exiger la clé même depuis la machine locale
OPENJARVIS_REQUIRE_AUTH=false
# Code d'identité initial optionnel (sinon défini dans l'écran de configuration)
OPENJARVIS_SECURITY_CODE=
```

L'adresse Expo n'est volontairement plus intégrée au code. Si `EXPO_PUBLIC_APP_URL`
n'est pas définie ou n'est pas une URL HTTP(S), le wrapper affiche un écran de
configuration et ne charge aucun serveur. Pour un serveur HTTP de développement,
utilisez uniquement une adresse locale de confiance. La configuration réseau de
`android/app/` concerne l'application native Kotlin ; elle ne s'applique pas
automatiquement à une compilation Expo distincte.

Côté interface web, renseignez la même clé dans *Paramètres* (ou `VITE_OPENJARVIS_API_KEY`) pour un accès distant.

> 🔒 **Important** : Ne committez jamais de clé d'API dans votre dépôt. Déclarez uniquement les variables requises dans `.env.example`.

---

## 8. Permissions Android

OpenJarvis déclare et utilise les permissions suivantes :

| Permission | Utilisation |
| :--- | :--- |
| `RECORD_AUDIO` | Reconnaissance vocale et commandes "Hey Jarvis" |
| `CAMERA` | Analyse visuelle, OCR et lecture de documents |
| `POST_NOTIFICATIONS` | Alertes de rappels et notifications de fin de tâche |
| `ACCESS_NETWORK_STATE` | Détection de l'état réseau en ligne / hors-ligne |
| `VIBRATE` | Retour haptique lors des interactions HUD |
| `FOREGROUND_SERVICE_MICROPHONE` | Écoute en arrière-plan avec notification persistante |
| `SYSTEM_ALERT_WINDOW` | Affichage du HUD flottant par-dessus d'autres applications |

---

## 9. Dépannage & FAQ

**Q : L'assistant vocal ne répond pas au micro ?**
> Vérifiez que l'autorisation Microphone est accordée dans les paramètres Android de l'application. Si vous utilisez le navigateur, autorisez le micro sur le domaine actif.

**Q : L'application fonctionne-t-elle sans clé Gemini ?**
> Oui ! OpenJarvis bascule automatiquement sur son moteur de raisonnement local, ses outils déterministes (calculatrice, rappels, intents) et le synthétiseur vocal intégré au système.

**Q : Comment activer le mode économie de batterie ?**
> Cliquez sur l'icône **Paramètres HUD** ou ouvrez le menu et activez le **Mode Éco Batterie**. Le framerate sera automatiquement limité à 30 fps et les filtres de flou allégés.

---

## 10. Licence & Références

Ce projet est distribué sous licence **Apache 2.0**.

```bibtex
@misc{saadfalcon2026openjarvispersonalaipersonal,
      title={OpenJarvis: Personal AI, On Personal Devices}, 
      author={Jon Saad-Falcon and Avanika Narayan and Robby Manihani and Tanvir Bhathal and Herumb Shandilya and Hakki Orhun Akengin and Gabriel Bo and Andrew Park and Matthew Hart and Caia Costello and Chuan Li and Christopher Ré and Azalia Mirhoseini},
      year={2026},
      eprint={2605.17172},
      archivePrefix={arXiv},
      primaryClass={cs.LG},
      url={https://arxiv.org/abs/2605.17172}, 
}
```


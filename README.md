# Securicam

Transformer un ancien smartphone Android en caméra de sécurité connectée avec :
- une application Android (capture + streaming),
- un backend Laravel (signalisation WebRTC + API),
- un frontend Angular (visualisation live + captures).

## Architecture cible

```text
Android Camera (Foreground Service + CameraX + WebRTC)
        │
        │ WebRTC (media) + WebSocket/HTTP (signaling)
        ▼
Laravel (Reverb/WebSockets + API REST + auth Sanctum)
        │
        ▼
Angular (lecteur live WebRTC + contrôles capture)
```

### Protocole retenu
**WebRTC** est retenu pour la latence ultra-faible (< 500ms), l’adaptation de qualité et le chiffrement natif (DTLS-SRTP).

---

## 1) Android (caméra)

### Stack
- Kotlin + Android natif
- CameraX (support API 21+, cible recommandée API 26+)
- Foreground Service (`foregroundServiceType="camera"`)
- WebRTC (`org.webrtc:google-webrtc`)
- OkHttp (signalisation)
- WorkManager (résilience/redémarrage)

### Permissions minimales
- `CAMERA`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CAMERA`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `RECEIVE_BOOT_COMPLETED`

### Exigences fonctionnelles
- Capture continue en service premier plan
- Streaming WebRTC vers navigateur
- Redémarrage robuste (`START_STICKY` + vérification périodique)
- Gestion des restrictions batterie (demande d’exemption + guide utilisateur)

---

## 2) Backend Laravel (signalisation + API)

### Stack
- Laravel 11.x
- Laravel Reverb (WebSockets)
- Broadcast events pour offer/answer/ICE
- API REST sécurisée via Sanctum

### API attendue (exemples)
- `POST /api/webrtc/offer`
- `POST /api/webrtc/answer`
- `POST /api/webrtc/ice-candidate`
- `POST /api/captures/photo`
- `POST /api/captures/video/start`
- `POST /api/captures/video/stop`

### Sécurité
- Authentification par token (Sanctum)
- WSS en production
- Contrôle d’accès aux canaux/caméras

---

## 3) Frontend Angular (viewer)

### Stack
- Angular 17+
- `RTCPeerConnection`
- Service de signalisation
- Composants `camera-viewer`, `capture-controls`, `gallery`

### Exigences fonctionnelles
- Affichage du flux live WebRTC
- État “EN DIRECT / ENREGISTREMENT”
- Capture photo à la demande
- Déclenchement start/stop enregistrement

---

## 4) Réseau externe (optionnel)

- TURN (coturn) recommandé si NAT/firewall strict
- Alternative simple de démarrage : Cloudflare Tunnel pour exposer Laravel

---

## 5) Contraintes opérationnelles

- Téléphone idéalement branché en continu (batterie/chauffe)
- Android recommandé : **8.0+ (API 26+)**
- Paramétrage constructeur anti-kill à documenter (Xiaomi/Huawei/Samsung…)

---

## 6) Plan de livraison recommandé

1. Android base (foreground + CameraX + permissions)
2. Android WebRTC + signalisation
3. Laravel Reverb + API
4. Angular viewer + contrôles
5. Tests d’intégration E2E
6. Durcissement sécurité + déploiement

---

## 7) État actuel du dépôt

Ce dépôt sert actuellement de **spécification d’architecture et de cadrage technique** pour implémenter la solution complète Android + Laravel + Angular décrite ci-dessus.

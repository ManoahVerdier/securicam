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

---

## 8) Démarrage rapide

Securicam supporte **deux modes de fonctionnement** :

| Mode | Quand l'utiliser | Stack | URL frontend |
|------|------------------|-------|--------------|
| **LAN / dev** | Caméras et viewer sur le même réseau wifi domestique | `docker-compose.yml` | `http://localhost:4200` |
| **Production VPS** | Caméras 4G + viewers Internet | `docker-compose.prod.yml` | `https://<votre-domaine>` |

Les deux modes restent fonctionnels en parallèle ; la config Android (debug vs
release) et celle du frontend (`environment.ts` vs `environment.prod.ts`)
basculent automatiquement.

### Mode LAN / développement

```bash
git clone https://github.com/ManoahVerdier/securicam.git
cd securicam
docker compose up -d
docker compose exec backend php artisan migrate

cd frontend && npm install && npm start          # http://localhost:4200
cd android && ./gradlew assembleDebug            # APK debug = HTTP autorisé sur LAN
```

Services :
- Frontend Angular : http://localhost:4200
- API Laravel : http://localhost:8000
- WebSocket Reverb : ws://localhost:8081

### Mode production (VPS public)

Voir [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) pour la procédure complète.
Résumé :

```bash
# Sur le VPS, après installation Docker + UFW
git clone https://github.com/ManoahVerdier/securicam.git /opt/securicam
cd /opt/securicam
cp .env.prod.example .env.prod         # éditer les secrets

# Émission du certificat puis stack complète
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build nginx
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
    --webroot -w /var/www/certbot -d <domaine> \
    --email <email> --agree-tos --no-eff-email
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

L'APK release (`./gradlew assembleRelease`) bascule automatiquement en HTTPS-only
via `network_security_config.xml`. L'APK debug conserve l'autorisation cleartext
LAN via l'overlay `src/debug/res/xml/network_security_config.xml`.

### Compiler l'application Android

```bash
cd android
./gradlew assembleDebug
```

---

## 9) Structure du projet

```
securicam/
├── android/          # Application Android (Kotlin)
├── backend/          # API Laravel 11.x
├── frontend/         # Frontend Angular 17+
├── docker/           # Configuration Docker
├── docs/             # Documentation
└── docker-compose.yml
```

---

## 10) Documentation

- [Guide d'exemption batterie](docs/BATTERY_OPTIMIZATION.md) - Configuration par fabricant
- [Guide de déploiement](docs/DEPLOYMENT.md) - Production avec Docker/Cloudflare

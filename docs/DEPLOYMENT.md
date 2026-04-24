# Déploiement Securicam — Procédure complète VPS (Apache + Docker)

Ce document décrit **pas à pas** le déploiement de Securicam sur un VPS sous
Debian/Ubuntu, avec Apache 2.4 en frontal HTTPS, les services applicatifs
(PHP-FPM, Reverb, MySQL, Redis) en conteneurs Docker isolés sur la loopback,
et coturn en host network pour le WebRTC.

> Le mode **dev LAN** (caméra et viewer sur le même réseau Wi-Fi via
> `docker compose up`) reste fonctionnel en parallèle, voir [README.md](../README.md).

---

## 0. Vue d'ensemble de l'architecture cible

```
                       Internet
                           │
                           ▼
            ┌──────────────────────────────┐
            │  Apache 2.4 (host, port 443) │   <- HTTPS Let's Encrypt
            │  - vhost SPA  (DocumentRoot) │
            │  - ProxyFcgi  -> :9000       │
            │  - ProxyWS    -> :8081/app   │
            └──────────────────────────────┘
                  │              │
       ┌──────────┘              └─────────┐
       ▼                                    ▼
 Docker (127.0.0.1:9000)         Docker (127.0.0.1:8081)
 ┌────────────────┐              ┌────────────────┐
 │ backend        │              │ reverb         │
 │ Laravel 11     │              │ WebSocket      │
 │ php-fpm        │              │ broadcasting   │
 └────────────────┘              └────────────────┘
       │                                    │
       └────────┐         ┌─────────────────┘
                ▼         ▼
        ┌──────────────────────┐    ┌──────────────────────┐
        │ db (MySQL 8)         │    │ redis (broadcast bus)│
        └──────────────────────┘    └──────────────────────┘

        ┌──────────────────────────────────────────┐
        │ coturn  (host network, UDP 3478 + 49152..) │   <- relais WebRTC
        └──────────────────────────────────────────┘
```

- **SPA Angular** : servie statiquement par Apache depuis `/var/www/securicam-spa`.
- **API + WebSocket** : Apache fait du reverse proxy vers les conteneurs Docker liés en `127.0.0.1`.
- **APK Android signé** : se connecte directement à `https://<DOMAINE>` et `wss://<DOMAINE>/app`.
- **TURN coturn** : nécessaire pour les pairs derrière du NAT symétrique (4G).

---

## 1. Pré-requis VPS

| Élément       | Version minimale | Notes                                        |
|---------------|------------------|----------------------------------------------|
| OS            | Debian 12 / Ubuntu 22.04+ | root SSH ou sudo                       |
| RAM           | 2 Go             | 4 Go recommandés                             |
| CPU           | 2 vCPU           |                                              |
| Disque        | 20 Go            | + place pour les captures uploadées          |
| Domaine       | DNS A pointant sur le VPS | ex. `securicam.example.com`         |
| Ports ouverts | TCP 80, 443      | Let's Encrypt + HTTPS                        |
|               | UDP 3478         | TURN/STUN                                    |
|               | UDP 49152-49500  | Plage relais coturn (configurable)           |
|               | TCP 3478         | (optionnel) TURN/TCP fallback                |

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y apache2 certbot python3-certbot-apache \
                    docker.io docker-compose-plugin git make rsync curl
sudo systemctl enable --now apache2 docker
sudo a2enmod proxy proxy_fcgi proxy_http proxy_wstunnel rewrite ssl headers
sudo systemctl reload apache2
```

---

## 2. Récupérer le projet

```bash
sudo mkdir -p /var/www/securicam.example.com/public_html
sudo chown -R $USER:$USER /var/www/securicam.example.com
cd /var/www/securicam.example.com/public_html
git clone https://github.com/<votre-fork>/securicam.git
cd securicam
```

Le chemin de référence dans la suite est :

```
/var/www/securicam.example.com/public_html/securicam
```

---

## 3. Configurer `.env.prod`

Toutes les commandes `make prod-*` lisent ce fichier (`docker-compose.prod.yml --env-file .env.prod`).

```bash
cp backend/.env.example .env.prod
nano .env.prod
```

Variables **obligatoires** à renseigner :

```ini
APP_NAME=Securicam
APP_ENV=production
APP_DEBUG=false
APP_URL=https://securicam.example.com

# Sera généré à l'étape 4 (ne PAS laisser vide)
APP_KEY=

# Base
DB_CONNECTION=mysql
DB_HOST=db
DB_PORT=3306
DB_DATABASE=securicam
DB_USERNAME=securicam
DB_PASSWORD=<mot-de-passe-fort>

# Redis (broadcast bus)
REDIS_HOST=redis
REDIS_PORT=6379

# Reverb / broadcasting (HTTPS / WSS via Apache proxy)
BROADCAST_CONNECTION=reverb
REVERB_APP_ID=securicam
REVERB_APP_KEY=<clé-aléatoire-32-chars>
REVERB_APP_SECRET=<secret-aléatoire-32-chars>
REVERB_HOST=0.0.0.0
REVERB_PORT=8081
REVERB_SCHEME=https
REVERB_SERVER_HOST=0.0.0.0
REVERB_SERVER_PORT=8081

# Sanctum (origines autorisées)
SANCTUM_STATEFUL_DOMAINS=securicam.example.com
SESSION_DOMAIN=.securicam.example.com

# SPA + APK (utilisés par make prod-spa-build et apk-release)
PUBLIC_HOST=securicam.example.com
TURN_HOST=securicam.example.com
TURN_USER=securicamturn
TURN_PASSWORD=<mot-de-passe-turn>
```

> 💡 Générer une clé/secret Reverb : `openssl rand -hex 16`

---

## 4. Générer `APP_KEY`

```bash
make prod-key
```

→ copier la valeur affichée dans `.env.prod` à la ligne `APP_KEY=`.

---

## 5. Démarrer les conteneurs (sans HTTPS encore)

```bash
make prod-up
```

Vérifier :

```bash
docker ps
# Doit lister : backend, reverb, db, redis, coturn (et éventuellement queue)

ss -tlnp | grep -E ':(9000|8081)'
# 127.0.0.1:9000  -> backend (php-fpm)
# 127.0.0.1:8081  -> reverb
```

Migrer la base :

```bash
make prod-migrate
```

---

## 6. Configurer Apache — étape 1 : vhost HTTP pour le challenge ACME

```bash
sudo cp docker/apache/securicam-pre-cert.conf \
        /etc/apache2/sites-available/securicam.conf
sudo nano /etc/apache2/sites-available/securicam.conf
```

Remplacer dans le fichier :

- `ServerName securicam.example.com` → votre domaine
- `DocumentRoot /var/www/securicam-spa` (laisser tel quel)

```bash
sudo mkdir -p /var/www/securicam-spa
echo "ok" | sudo tee /var/www/securicam-spa/index.html
sudo a2ensite securicam.conf
sudo a2dissite 000-default.conf 2>/dev/null || true
sudo apachectl configtest && sudo systemctl reload apache2
```

Vérifier :

```bash
curl http://securicam.example.com
# -> ok
```

---

## 7. Émettre le certificat Let's Encrypt

```bash
make prod-cert DOMAIN=securicam.example.com EMAIL=admin@example.com
```

certbot va :
1. Valider le challenge HTTP-01.
2. Modifier le vhost pour ajouter le bloc TLS et activer `Redirect http→https`.
3. Installer le timer de renouvellement systemd (`certbot.timer`).

Tester le renouvellement :

```bash
sudo certbot renew --dry-run
```

---

## 8. Configurer Apache — étape 2 : vhost final SPA + Proxy

Remplacer le vhost par la version production complète :

```bash
sudo cp docker/apache/securicam.conf /etc/apache2/sites-available/securicam.conf
sudo nano /etc/apache2/sites-available/securicam.conf
```

À ajuster dans le fichier :

- `ServerName` et tous les `ServerAlias`
- Chemins des certificats (`SSLCertificateFile`, `SSLCertificateKeyFile`) — déjà installés par certbot dans `/etc/letsencrypt/live/<domaine>/`
- `SetEnvIf Origin` (CORS) si vous utilisez un sous-domaine séparé pour l'API

Recharger :

```bash
sudo apachectl configtest && sudo systemctl reload apache2
```

Tester :

```bash
curl -I https://securicam.example.com/api/health      # 200 (ou 404 si la route n'existe pas — c'est ok)
curl -I https://securicam.example.com/                # 200, doit servir l'index SPA
```

---

## 9. Construire et déployer la SPA Angular

Le build se fait dans un conteneur Node 20 isolé (pas besoin de Node sur le VPS).
La cible Make ci-dessous lit `PUBLIC_HOST`, `TURN_HOST`, `TURN_USER`, `TURN_PASSWORD`
depuis `.env.prod` et les injecte dans `environment.prod.ts` avant `ng build`.

```bash
make prod-spa-build
```

Le build est ensuite copié vers `/var/www/securicam-spa` (configurable via
`SPA_DEPLOY_DIR=/autre/chemin make prod-spa-build`).

Recharger Apache (cache statique) :

```bash
sudo systemctl reload apache2
```

---

## 10. Configurer coturn (relais TURN)

coturn tourne en `network_mode: host` dans `docker-compose.prod.yml`. Vérifier
le fichier `docker/coturn/turnserver.conf` :

```ini
listening-port=3478
fingerprint
lt-cred-mech
realm=securicam.example.com
user=securicamturn:<mot-de-passe-turn>      # = TURN_PASSWORD de .env.prod
no-tls
no-dtls
min-port=49152
max-port=49500
external-ip=<IP_PUBLIQUE_DU_VPS>
```

Tester depuis votre poste :

```bash
# Outil web : https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/
# - STUN URI : stun:securicam.example.com:3478
# - TURN URI : turn:securicam.example.com:3478?transport=udp
# - Username : securicamturn
# - Credential : <mot-de-passe-turn>
# Vous devez voir une candidate de type "relay".
```

---

## 11. Construire et installer l'APK release

Sur votre poste de dev (pas sur le VPS) :

```bash
# 1. Générer une fois la keystore (si pas déjà fait)
#    -> mot de passe et alias seront à conserver pour TOUTES les futures versions
bash scripts/gen-release-keystore.sh

# 2. Renseigner android/app/keystore/release.properties :
#    storeFile=keystore/release.keystore
#    storePassword=<motDePasse>
#    keyAlias=securicam
#    keyPassword=<motDePasse>

# 3. Build
make apk-release
# -> android/app/build/outputs/apk/release/app-release.apk
```

Installer sur le téléphone :

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Dans l'app : se connecter avec un compte créé via la SPA, autoriser caméra +
batterie sans restriction (cf. [docs/BATTERY_OPTIMIZATION.md](BATTERY_OPTIMIZATION.md)).

---

## 12. Liste complète des commandes Make

> ⚠️ **Attention au tiret** : `make prod-up` (avec tiret) et **non**
> `make prod up` (avec espace). L'erreur `make: *** No rule to make target 'prod'` vient de là.

| Commande                                          | Description                                                  |
|---------------------------------------------------|--------------------------------------------------------------|
| `make dev-up`                                     | Démarre la stack Docker locale (mode LAN dev)                |
| `make dev-down`                                   | Arrête la stack dev                                          |
| `make dev-logs SVC=backend`                       | Suit les logs d'un service dev                               |
| `make dev-migrate`                                | Migre la DB en mode dev                                      |
| `make dev-shell`                                  | Shell dans le container backend dev                          |
| `make prod-up`                                    | Démarre la stack production (avec rebuild)                   |
| `make prod-down`                                  | Arrête la stack production                                   |
| `make prod-logs SVC=backend`                      | Suit les logs d'un service prod                              |
| `make prod-migrate`                               | Migre la DB en prod (avec `--force`)                         |
| `make prod-key`                                   | Génère un `APP_KEY` Laravel (à recopier dans `.env.prod`)    |
| `make prod-spa-build`                             | Build la SPA Angular et la déploie dans `SPA_DEPLOY_DIR`     |
| `make prod-cert DOMAIN=… EMAIL=…`                 | Émet/installe le cert Let's Encrypt via certbot+apache       |
| `make prod-renew`                                 | Renouvelle le cert et reload Apache                          |
| `make apk-debug`                                  | Build l'APK debug (cleartext autorisé pour LAN)              |
| `make apk-release`                                | Build l'APK release signé (HTTPS uniquement)                 |

---

## 13. Mise à jour applicative (déploiement continu)

Procédure standard pour pousser une nouvelle version :

```bash
cd /var/www/securicam.example.com/public_html/securicam
git pull

# Backend (rebuild image + migrations)
make prod-up
make prod-migrate

# SPA (rebuild + redeploy)
make prod-spa-build
sudo systemctl reload apache2

# (Optionnel) APK : rebuild côté dev, distribuer le nouvel APK aux téléphones
make apk-release
```

---

## 14. Sauvegardes

À programmer **hors** du cycle Docker (ex. cron host) :

```bash
# Dump MySQL quotidien
docker exec securicam-db mysqldump -uroot -p<root_pwd> securicam \
  | gzip > /var/backups/securicam-db-$(date +\%F).sql.gz

# Captures uploadées (volume Docker)
sudo rsync -a /var/lib/docker/volumes/securicam_storage/_data/ \
              /var/backups/securicam-storage/
```

---

## 15. Dépannage — erreurs fréquentes

### `make: *** No rule to make target 'prod'`
Vous avez tapé `make prod up` au lieu de `make prod-up` (avec un tiret).

### `Connection refused` sur 9000 ou 8081
- `docker ps` : les conteneurs sont-ils up ?
- `docker compose -f docker-compose.prod.yml --env-file .env.prod logs backend reverb`
- Vérifier que `.env.prod` a bien été lu : la commande `make prod-up` doit être lancée depuis le **répertoire racine** du projet.

### `Mixed Content` dans la console du navigateur
- L'environnement Angular pointe encore en `http://` ou `ws://`. Rebuilder la SPA :
  ```bash
  make prod-spa-build
  ```

### WebSocket bloqué (`WebSocket connection failed`)
- Vérifier que `mod_proxy_wstunnel` est activé : `apachectl -M | grep wstunnel`.
- Vérifier le bloc `ProxyPass /app ws://127.0.0.1:8081/app` dans le vhost.
- Tester directement le port via le tunnel SSH : `ssh -L 8081:127.0.0.1:8081 vps` puis `wscat -c ws://localhost:8081/app`.

### WebRTC reste en `checking` puis échoue (4G ↔ Wi-Fi)
- Le TURN n'est pas joignable. Re-tester avec l'outil trickle-ice.
- Vérifier `external-ip=` dans `turnserver.conf` (IP publique réelle).
- Vérifier que le firewall VPS laisse passer UDP 3478 + UDP 49152-49500.

### `lintVitalRelease` échoue lors de `make apk-release`
- Vérifier que `tools:node="remove"` est bien présent sur le `<meta-data>`
  `androidx.work.WorkManagerInitializer` dans `android/app/src/main/AndroidManifest.xml`.

### Mot de passe keystore avec `!` refusé en bash
- `!` déclenche l'expansion d'historique. Soit utiliser des guillemets simples
  `'Mot!Passe'`, soit éviter `!` dans le mot de passe.

### Resignature impossible (`Failed to find signer`)
- Une nouvelle keystore a été générée → la signature ne correspond plus à la
  version précédemment installée. Désinstaller manuellement l'app sur le
  téléphone (`adb uninstall com.securicam.camera`) avant de réinstaller.

---

## 16. Désinstallation / nettoyage complet

```bash
make prod-down
docker volume rm securicam_db_data securicam_redis_data securicam_storage 2>/dev/null || true
sudo a2dissite securicam.conf
sudo rm /etc/apache2/sites-available/securicam.conf
sudo certbot delete --cert-name securicam.example.com
sudo systemctl reload apache2
sudo rm -rf /var/www/securicam-spa /var/www/securicam.example.com
```

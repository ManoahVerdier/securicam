# Déploiement Securicam — Procédure complète VPS (Apache + Docker)

Cette procédure déploie Securicam sur un VPS public Ubuntu 22.04+ avec :

- **Apache 2.4** sur l'host (TLS Let's Encrypt, SPA, reverse proxy)
- **Docker Compose** pour Laravel (php-fpm), Reverb, MySQL, Redis, coturn
- Domaine : `securicam.verdier-developpement.fr` (à adapter)

> Le mode dev LAN avec `docker compose up` reste 100 % fonctionnel et indépendant.

---

## Topologie réseau

```
Internet
  │  HTTPS 443  ───────────────────────────► Apache 2.4 (host)
  │  STUN/TURN 3478 + 49160-49200/udp ─────► coturn (host network)
  ▼
                       127.0.0.1:9000  ──── php-fpm    (container backend)
                       127.0.0.1:8081  ──── Reverb     (container reverb)
                                            MySQL      (container db, internal)
                                            Redis      (container redis, internal)
```

| Port           | Proto      | Usage                              |
|----------------|------------|------------------------------------|
| 22             | TCP        | SSH                                |
| 80             | TCP        | HTTP (redirect 301 + ACME)         |
| 443            | TCP        | HTTPS + WSS                        |
| 3478           | UDP+TCP    | STUN/TURN                          |
| 49160-49200    | UDP        | TURN media relay                   |

Les containers Docker n'écoutent **que sur 127.0.0.1**, jamais publiquement.

---

## 1. Préparer le VPS

### 1.1. Système

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y ca-certificates curl gnupg git make rsync
sudo timedatectl set-timezone Europe/Paris
```

### 1.2. Apache + modules nécessaires

```bash
sudo apt install -y apache2 certbot python3-certbot-apache
sudo a2enmod ssl rewrite headers proxy proxy_http proxy_fcgi proxy_wstunnel http2
sudo systemctl enable --now apache2

# Désactive le site par défaut pour éviter les conflits :
sudo a2dissite 000-default
```

### 1.3. Docker

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
newgrp docker
```

### 1.4. Pare-feu (UFW)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw allow 80,443/tcp
sudo ufw allow 3478
sudo ufw allow 49160:49200/udp
sudo ufw enable
sudo ufw status
```

### 1.5. DNS

Créer un enregistrement **A** :
`securicam.verdier-developpement.fr.  IN  A  <IP_publique_du_VPS>`

Vérification :

```bash
dig +short securicam.verdier-developpement.fr  # doit renvoyer l'IP du VPS
```

---

## 2. Cloner le projet et configurer les secrets

```bash
sudo mkdir -p /opt/securicam && sudo chown $USER:$USER /opt/securicam
git clone https://github.com/ManoahVerdier/securicam.git /opt/securicam
cd /opt/securicam
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

### 2.1. Générer la clef Laravel

```bash
make prod-key
# copie la sortie "base64:..." dans APP_KEY=... du fichier .env.prod
```

### 2.2. Renseigner `.env.prod`

Éditer chaque variable :

```bash
PUBLIC_HOST=securicam.verdier-developpement.fr
PUBLIC_IP=<IP_publique_du_VPS>
APP_KEY=base64:<générée à l'étape précédente>
DB_PASSWORD=<mot de passe long aléatoire>
DB_ROOT_PASSWORD=<mot de passe long aléatoire>
REVERB_APP_SECRET=<mot de passe long aléatoire>
TURN_PASSWORD=<mot de passe long aléatoire>
```

> Astuce : `openssl rand -base64 32` pour chaque secret.

---

## 3. Démarrer les containers

```bash
make prod-up           # build + démarre backend, reverb, db, redis, coturn
make prod-migrate      # applique les migrations Laravel
docker compose -f docker-compose.prod.yml ps
```

À ce stade :

- `curl http://127.0.0.1:9000` répond du php-fpm (FastCGI, pas du HTTP brut)
- `curl http://127.0.0.1:8081/app/securicam-app-key` doit ouvrir un WS handshake
- `nc -uv <PUBLIC_IP> 3478` répond depuis coturn

---

## 4. Configurer Apache

### 4.1. Préparer le DocumentRoot SPA

```bash
sudo mkdir -p /var/www/securicam-spa
sudo chown -R www-data:www-data /var/www/securicam-spa
echo "Securicam — bootstrap" | sudo tee /var/www/securicam-spa/index.html
```

### 4.2. Activer le vhost (avant TLS)

```bash
sudo cp docker/apache/securicam-pre-cert.conf /etc/apache2/sites-available/securicam.conf
# remplace ${PUBLIC_HOST} par le vrai domaine :
sudo sed -i "s|\${PUBLIC_HOST}|securicam.verdier-developpement.fr|g" \
    /etc/apache2/sites-available/securicam.conf
sudo a2ensite securicam
sudo apache2ctl configtest && sudo systemctl reload apache2
```

Vérifie : `curl -I http://securicam.verdier-developpement.fr` répond 200 sur la
page bootstrap.

### 4.3. Émettre le certificat Let's Encrypt

```bash
make prod-cert DOMAIN=securicam.verdier-developpement.fr EMAIL=toi@example.com
# (équivalent à : sudo certbot --apache -d <domaine> -m <email> --agree-tos --redirect)
```

Certbot crée automatiquement `securicam-le-ssl.conf` et active SSL.

### 4.4. Remplacer par le vhost final (TLS + proxy + SPA)

```bash
sudo cp docker/apache/securicam.conf /etc/apache2/sites-available/securicam.conf
sudo sed -i "s|\${PUBLIC_HOST}|securicam.verdier-developpement.fr|g" \
    /etc/apache2/sites-available/securicam.conf
sudo apache2ctl configtest && sudo systemctl reload apache2
```

> Le fichier `securicam-le-ssl.conf` que certbot a généré n'est plus nécessaire :
> `sudo a2dissite securicam-le-ssl && sudo systemctl reload apache2`.

Renouvellement automatique (déjà installé par le paquet certbot via systemd
timer ; vérifier `systemctl list-timers | grep certbot`).

---

## 5. Déployer la SPA Angular

```bash
make prod-spa-build
```

Ce target :

1. lit les variables `PUBLIC_HOST`, `TURN_HOST`, `TURN_USER`, `TURN_PASSWORD`
   du `.env.prod` ;
2. builde un container Docker temporaire avec Node 20 + Angular CLI ;
3. substitue les placeholders dans `environment.prod.ts` ;
4. produit la SPA optimisée et la copie dans `/var/www/securicam-spa/` ;
5. fixe les permissions www-data.

À refaire après chaque mise à jour du frontend.

---

## 6. Créer le premier utilisateur et la première caméra

```bash
docker compose -f docker-compose.prod.yml exec backend php artisan tinker
```

```php
$user = \App\Models\User::create([
    'name'     => 'Owner',
    'email'    => 'owner@example.com',
    'password' => bcrypt('un_mot_de_passe_solide'),
]);

$camera = \App\Models\Camera::create([
    'user_id' => $user->id,
    'name'    => 'Téléphone salon',
]);

echo $user->createToken('android-camera')->plainTextToken;
// => "1|abcdef..."  ← reporte ce token dans l'app Android
```

---

## 7. Configurer les clients

### 7.1. Navigateur (viewer)

Ouvre <https://securicam.verdier-developpement.fr>, login. Aucune config
manuelle d'ICE : `environment.prod.ts` injecte STUN+TURN automatiquement.

### 7.2. Application Android (caméra)

Installer l'APK release (`android/app/build/outputs/apk/release/app-release.apk`)
puis dans la config :

| Champ          | Valeur                                                |
|----------------|-------------------------------------------------------|
| Server URL     | `https://securicam.verdier-developpement.fr/api`      |
| Auth token     | Le token Sanctum imprimé ci-dessus                    |
| Camera ID      | L'ID de la caméra créée                               |
| TURN Host      | `securicam.verdier-developpement.fr`                  |
| TURN User      | Valeur de `TURN_USER` du `.env.prod`                  |
| TURN Password  | Valeur de `TURN_PASSWORD` du `.env.prod`              |

> L'APK release n'autorise QUE HTTPS. Pour tester en LAN, utilise l'APK debug
> (`make apk-debug`) qui conserve l'autorisation cleartext sur le réseau local
> via `src/debug/res/xml/network_security_config.xml`.

---

## 8. Vérifications

```bash
# Apache (TLS, redirections)
curl -I http://securicam.verdier-developpement.fr     # 301 -> https
curl -I https://securicam.verdier-developpement.fr    # 200 + HSTS

# API Laravel
curl https://securicam.verdier-developpement.fr/api/cameras   # 401 (auth requise = OK)

# Reverb (handshake WS)
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     -H "Sec-WebSocket-Version: 13" \
     https://securicam.verdier-developpement.fr/app/securicam-app-key   # 101 attendu

# coturn STUN/TURN — depuis l'extérieur :
# https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/
#   stun:securicam.verdier-developpement.fr:3478
#   turn:securicam.verdier-developpement.fr:3478 + user/password de .env.prod
# On doit voir des candidats "srflx" (STUN) ET "relay" (TURN).
```

### Logs

```bash
sudo tail -f /var/log/apache2/securicam-error.log /var/log/apache2/securicam-access.log
docker compose -f docker-compose.prod.yml logs -f backend reverb coturn
```

---

## 9. Mise à jour

```bash
cd /opt/securicam
git pull

# Backend
make prod-up                   # rebuild + restart containers
make prod-migrate              # nouvelles migrations

# Frontend
make prod-spa-build            # nouveau bundle SPA → /var/www/securicam-spa
```

Apache n'a pas besoin d'être rechargé sauf si tu modifies `docker/apache/securicam.conf` :

```bash
sudo cp docker/apache/securicam.conf /etc/apache2/sites-available/securicam.conf
sudo sed -i "s|\${PUBLIC_HOST}|securicam.verdier-developpement.fr|g" \
    /etc/apache2/sites-available/securicam.conf
sudo apache2ctl configtest && sudo systemctl reload apache2
```

---

## 10. Sauvegarde

```bash
# Base de données
docker compose -f docker-compose.prod.yml exec db \
    mysqldump -u securicam -p$DB_PASSWORD securicam | gzip > /opt/backups/securicam-$(date +%F).sql.gz

# Captures (storage)
tar czf /opt/backups/storage-$(date +%F).tar.gz -C /opt/securicam/backend/storage app/

# Secrets (sur poste local seulement, pas de copie sur le VPS) :
#   - .env.prod
#   - android/app/keystore/release.keystore + release.properties
```

---

## 11. Diagnostic — problèmes fréquents

| Symptôme                                       | Cause probable                      | Solution                                                                       |
|------------------------------------------------|-------------------------------------|--------------------------------------------------------------------------------|
| `502 Bad Gateway` sur `/api/...`               | backend container down              | `docker compose -f docker-compose.prod.yml ps`, puis `logs backend`            |
| `502` sur `/app` (WS)                          | reverb container down               | `logs reverb`                                                                  |
| Pas de candidats `relay` dans trickle-ice      | UDP 49160-49200 non ouverts         | `sudo ufw status`, vérifier `external-ip` dans coturn                          |
| `ssl_error_no_cypher_overlap` côté navigateur  | mods Apache manquants               | `sudo a2enmod ssl http2 headers && sudo systemctl reload apache2`              |
| `419 PAGE EXPIRED` côté API                    | `SESSION_DOMAIN` mal défini         | doit valoir `securicam.verdier-developpement.fr` dans `.env.prod`              |
| App Android rejette la connexion HTTPS         | mauvais certificat / chaîne         | `openssl s_client -connect domaine:443 -servername domaine`                    |
| WebRTC stuck en `checking`                     | TURN credentials incorrectes        | revérifier les 3 champs TURN dans l'app Android et dans `.env.prod`            |

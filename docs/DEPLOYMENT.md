# Déploiement Securicam (production)

Ce guide décrit le déploiement de Securicam sur un VPS public avec un nom de
domaine et HTTPS de bout en bout. C'est la topologie cible pour permettre à des
téléphones Android (caméras) et à des navigateurs (viewers) sur n'importe quel
réseau de se connecter sans configuration particulière côté client.

## 1. Topologie réseau

```
[ Téléphone Android caméra ]              [ Navigateur viewer ]
            \                                      /
        HTTPS/WSS (443)                  HTTPS/WSS (443)
              \                                /
              [ VPS public  securicam.verdier-developpement.fr ]
                          |
        ┌─────────────────┼──────────────────────────┐
        │                 │                          │
   nginx (TLS)        coturn (TURN)              redis/db
   ├─ /            → SPA Angular            (réseau interne docker)
   ├─ /api         → Laravel (php-fpm)
   ├─ /broadcasting/auth, /sanctum
   └─ /app         → Reverb (WebSocket)
```

Le serveur **TURN/STUN coturn** est indispensable : sans relai TURN, les
candidats ICE ne parviennent pas à traverser un NAT carrier-grade (4G, certains
FAI). On expose donc :

| Port           | Protocole | Rôle                                      |
|----------------|-----------|-------------------------------------------|
| 80             | TCP       | ACME (Let's Encrypt) + redirection HTTPS  |
| 443            | TCP       | HTTPS + WSS                               |
| 3478           | UDP+TCP   | STUN/TURN (signalisation)                 |
| 49160-49200    | UDP       | TURN (média relayé)                       |

## 2. Prérequis VPS

- Ubuntu 22.04 LTS (ou équivalent)
- 2 vCPU / 4 Go RAM minimum (4 vCPU / 8 Go recommandé selon le nombre de
  flux WebRTC simultanés)
- IP publique fixe
- Enregistrement DNS `A` :
  `securicam.verdier-developpement.fr` → `<IP_VPS>`

### Installation Docker

```bash
sudo apt update && sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
    sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
    sudo tee /etc/apt/sources.list.d/docker.list
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
```

### Pare-feu (UFW)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp        # SSH
sudo ufw allow 80,443/tcp    # HTTP + HTTPS
sudo ufw allow 3478          # STUN/TURN
sudo ufw allow 49160:49200/udp   # TURN media relay
sudo ufw enable
```

## 3. Récupération du code & configuration

```bash
sudo mkdir -p /opt/securicam && sudo chown $USER /opt/securicam
git clone https://github.com/<vous>/securicam.git /opt/securicam
cd /opt/securicam
cp .env.prod.example .env.prod
```

Éditer `.env.prod` :

- `PUBLIC_HOST=securicam.verdier-developpement.fr`
- `PUBLIC_IP=<IP_publique_du_VPS>`
- Générer la clef Laravel :
  ```bash
  docker run --rm -v "$PWD/backend:/app" -w /app composer:2 \
      sh -c "composer install --no-dev --no-interaction && php artisan key:generate --show"
  ```
  Copier la valeur affichée dans `APP_KEY=base64:...`
- Choisir des mots de passe forts pour `DB_*`, `TURN_PASSWORD`, `REDIS_PASSWORD`
  (si activé), etc.

## 4. Premier démarrage

L'idée : lancer d'abord les services qui n'ont pas besoin de TLS pour permettre
à certbot d'émettre le certificat, puis activer nginx HTTPS.

### 4.1. Émission du certificat Let's Encrypt

```bash
# Démarre seulement nginx (qui sert temporairement /.well-known sur :80)
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build nginx

# Émettre le certificat
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
    --webroot -w /var/www/certbot \
    -d securicam.verdier-developpement.fr \
    --email votre-email@example.com \
    --agree-tos --no-eff-email

# Recharger nginx pour prendre en compte les nouveaux certificats
docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
```

### 4.2. Démarrage complet

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml ps
```

Services attendus : `nginx`, `backend`, `reverb`, `db`, `redis`, `coturn`.

### 4.3. Renouvellement automatique

Crontab :

```cron
0 3 * * * cd /opt/securicam && docker compose -f docker-compose.prod.yml --env-file .env.prod run --rm certbot renew && docker compose -f docker-compose.prod.yml --env-file .env.prod exec nginx nginx -s reload
```

## 5. Création des utilisateurs / caméras

```bash
docker compose -f docker-compose.prod.yml exec backend php artisan tinker
```

```php
$user = \App\Models\User::create([
    'name' => 'Owner',
    'email' => 'owner@example.com',
    'password' => bcrypt('un_mot_de_passe_solide'),
]);
$camera = \App\Models\Camera::create([
    'user_id' => $user->id,
    'name' => 'Téléphone salon',
]);
$token = $user->createToken('android-camera')->plainTextToken;
echo $token; // à reporter dans l'app Android
```

## 6. Configuration des clients

### 6.1. Navigateur (viewer)

Ouvrir https://securicam.verdier-developpement.fr , se connecter, sélectionner
une caméra, panneau d'info → "Démarrer le streaming". Aucune configuration
manuelle d'ICE n'est nécessaire : `environment.prod.ts` injecte déjà le STUN/TURN
public.

### 6.2. Téléphone Android (caméra)

Dans l'application Securicam :

| Champ              | Valeur                                                |
|--------------------|-------------------------------------------------------|
| Server URL         | `https://securicam.verdier-developpement.fr/api`      |
| Auth token         | Le token Sanctum généré ci-dessus                     |
| Camera ID          | L'ID de la caméra créée                               |
| TURN Host          | `securicam.verdier-developpement.fr`                  |
| TURN User          | Valeur de `TURN_USER` du `.env.prod`                  |
| TURN Password      | Valeur de `TURN_PASSWORD` du `.env.prod`              |

> Le build release de l'APK n'autorise que HTTPS (`network_security_config.xml`
> base-config). Le build debug conserve l'autorisation cleartext pour le LAN
> via `src/debug/res/xml/network_security_config.xml`.

## 7. Diagnostic

```bash
# Logs nginx (TLS, requêtes)
docker compose -f docker-compose.prod.yml logs -f nginx

# Logs Laravel + Reverb
docker compose -f docker-compose.prod.yml logs -f backend reverb

# Test TURN depuis l'extérieur (https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/)
#   STUN : stun:securicam.verdier-developpement.fr:3478
#   TURN : turn:securicam.verdier-developpement.fr:3478, user/password de .env.prod
# On doit voir des candidats "srflx" (STUN) et "relay" (TURN).

# Logs coturn
docker compose -f docker-compose.prod.yml logs -f coturn
```

## 8. Mise à jour

```bash
cd /opt/securicam
git pull
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
docker compose -f docker-compose.prod.yml exec backend php artisan migrate --force
```

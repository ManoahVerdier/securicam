# Guide de déploiement Securicam

## Prérequis

- Docker et Docker Compose
- Domaine avec certificat SSL (pour HTTPS/WSS)
- Ou : Cloudflare Tunnel pour exposer facilement

## Déploiement local (développement)

### 1. Cloner le dépôt

```bash
git clone https://github.com/ManoahVerdier/securicam.git
cd securicam
```

### 2. Configurer le backend

```bash
cd backend
cp .env.example .env
composer install
# Générer une clé d'application
php artisan key:generate
```

### 3. Lancer avec Docker Compose

```bash
cd ..
docker-compose up -d
```

### 4. Initialiser la base de données

```bash
docker-compose exec backend php artisan migrate
docker-compose exec backend php artisan db:seed
```

### 5. Accès

- **Frontend Angular** : http://localhost:4200
- **API Laravel** : http://localhost:8000
- **WebSocket Reverb** : ws://localhost:8080

---

## Déploiement production

### Option A : Serveur dédié avec Nginx

#### 1. Installation du serveur

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install nginx certbot python3-certbot-nginx
```

#### 2. Configuration Nginx

Créez `/etc/nginx/sites-available/securicam`:

```nginx
server {
    listen 80;
    server_name api.securicam.example.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name api.securicam.example.com;
    
    ssl_certificate /etc/letsencrypt/live/api.securicam.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.securicam.example.com/privkey.pem;
    
    root /var/www/securicam/backend/public;
    index index.php;
    
    location / {
        try_files $uri $uri/ /index.php?$query_string;
    }
    
    location ~ \.php$ {
        fastcgi_pass unix:/var/run/php/php8.3-fpm.sock;
        fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;
        include fastcgi_params;
    }
    
    # WebSocket pour Reverb
    location /app {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }
}
```

#### 3. SSL avec Let's Encrypt

```bash
sudo certbot --nginx -d api.securicam.example.com
```

#### 4. Démarrer Reverb comme service

Créez `/etc/systemd/system/securicam-reverb.service`:

```ini
[Unit]
Description=Securicam Reverb WebSocket Server
After=network.target

[Service]
User=www-data
Group=www-data
WorkingDirectory=/var/www/securicam/backend
ExecStart=/usr/bin/php artisan reverb:start --host=127.0.0.1 --port=8080
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable securicam-reverb
sudo systemctl start securicam-reverb
```

### Option B : Cloudflare Tunnel (recommandé pour simplicité)

#### 1. Installer cloudflared

```bash
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o cloudflared
chmod +x cloudflared
sudo mv cloudflared /usr/local/bin/
```

#### 2. Authentification

```bash
cloudflared tunnel login
```

#### 3. Créer le tunnel

```bash
cloudflared tunnel create securicam
```

#### 4. Configurer le tunnel

Créez `~/.cloudflared/config.yml`:

```yaml
tunnel: securicam
credentials-file: /root/.cloudflared/<TUNNEL_ID>.json

ingress:
  - hostname: api.securicam.example.com
    service: http://localhost:8000
  - hostname: ws.securicam.example.com
    service: http://localhost:8080
  - hostname: app.securicam.example.com
    service: http://localhost:4200
  - service: http_status:404
```

#### 5. Démarrer le tunnel

```bash
cloudflared tunnel run securicam
```

---

## Configuration Android

### 1. Compiler l'APK

```bash
cd android
./gradlew assembleRelease
```

L'APK se trouve dans `app/build/outputs/apk/release/`

### 2. Configurer l'application

Sur le téléphone :
1. Installer l'APK
2. Entrer l'URL du serveur : `https://api.securicam.example.com/api`
3. Générer un token d'authentification via l'API
4. Entrer l'ID de la caméra
5. Activer le démarrage automatique
6. Suivre le guide d'exemption de batterie

---

## Configuration production (.env)

```env
APP_ENV=production
APP_DEBUG=false
APP_URL=https://api.securicam.example.com

FRONTEND_URL=https://app.securicam.example.com

DB_CONNECTION=mysql
DB_HOST=127.0.0.1
DB_PORT=3306
DB_DATABASE=securicam
DB_USERNAME=securicam
DB_PASSWORD=strong_password_here

BROADCAST_CONNECTION=reverb
REVERB_APP_ID=securicam
REVERB_APP_KEY=random_key_here
REVERB_APP_SECRET=random_secret_here
REVERB_HOST=ws.securicam.example.com
REVERB_PORT=443
REVERB_SCHEME=https
```

---

## Sécurité

### Checklist

- [ ] HTTPS activé partout
- [ ] WSS pour WebSocket en production
- [ ] Tokens d'authentification avec expiration
- [ ] Pare-feu configuré (ports 80, 443 uniquement)
- [ ] Mises à jour de sécurité automatiques
- [ ] Sauvegardes régulières de la base de données

### TURN Server (optionnel)

Pour les réseaux avec NAT strict, installez coturn :

```bash
sudo apt install coturn
```

Configuration `/etc/turnserver.conf`:

```
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
user=securicam:password
realm=securicam.example.com
cert=/etc/letsencrypt/live/turn.securicam.example.com/fullchain.pem
pkey=/etc/letsencrypt/live/turn.securicam.example.com/privkey.pem
```

Ajoutez les serveurs TURN dans la configuration WebRTC de l'app Android et du frontend Angular.

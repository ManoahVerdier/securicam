# Securicam — helper targets for both LAN dev and Apache+Docker production modes.
#
# Dev (LAN):
#   make dev-up              - start dockerized stack
#   make dev-down            - stop it
#   make dev-logs SVC=backend
#
# Production (VPS, Apache host + Docker backend):
#   make prod-up             - bring up backend/reverb/db/redis/coturn
#   make prod-down
#   make prod-logs SVC=backend
#   make prod-migrate
#   make prod-key            - generate Laravel APP_KEY
#   make prod-spa-build      - build Angular SPA, deploy to /var/www/securicam-spa
#   make prod-cert DOMAIN=xx EMAIL=yy   - issue Let's Encrypt cert via certbot+apache
#   make prod-renew
#
# Android APK:
#   make apk-debug           - debug APK (cleartext LAN allowed)
#   make apk-release         - signed release APK (HTTPS-only)

DC      := docker compose
DC_PROD := docker compose -f docker-compose.prod.yml --env-file .env.prod

PROJECT_DIR := $(shell pwd)
SPA_DEPLOY_DIR ?= /var/www/securicam-spa

# ---------------------------------------------------------------- DEV / LAN --
.PHONY: dev-up dev-down dev-logs dev-migrate dev-shell

dev-up:
	$(DC) up -d

dev-down:
	$(DC) down

dev-logs:
	$(DC) logs -f $(SVC)

dev-migrate:
	$(DC) exec backend php artisan migrate

dev-shell:
	$(DC) exec backend bash

# ---------------------------------------------------------------- PRODUCTION
.PHONY: prod-up prod-down prod-logs prod-migrate prod-key prod-spa-build prod-cert prod-renew

prod-up:
	$(DC_PROD) up -d --build

prod-down:
	$(DC_PROD) down

prod-logs:
	$(DC_PROD) logs -f $(SVC)

prod-migrate:
	$(DC_PROD) exec backend php artisan migrate --force

prod-key:
	@echo "Generating Laravel APP_KEY (paste it into .env.prod):"
	@printf "base64:%s\n" "$$(openssl rand -base64 32)"

# Build the Angular SPA inside a transient container, then copy artefacts to
# $(SPA_DEPLOY_DIR). Reads PUBLIC_HOST/TURN_* from .env.prod.
prod-spa-build:
	@if [ ! -f .env.prod ]; then echo ".env.prod missing"; exit 1; fi
	set -a; . ./.env.prod; set +a; \
	docker build -f docker/frontend/Dockerfile.build \
		--build-arg PUBLIC_HOST=$$PUBLIC_HOST \
		--build-arg TURN_HOST=$$TURN_HOST \
		--build-arg TURN_USER=$$TURN_USER \
		--build-arg TURN_PASSWORD=$$TURN_PASSWORD \
		-t securicam-spa-build .
	@echo "Deploying SPA to $(SPA_DEPLOY_DIR)"
	@sudo mkdir -p $(SPA_DEPLOY_DIR)
	@docker rm -f securicam-spa-extract >/dev/null 2>&1 || true
	@docker create --name securicam-spa-extract securicam-spa-build >/dev/null
	@docker cp securicam-spa-extract:/app/dist/securicam-viewer/browser/. /tmp/securicam-spa/
	@sudo rsync -a --delete /tmp/securicam-spa/ $(SPA_DEPLOY_DIR)/
	@sudo chown -R www-data:www-data $(SPA_DEPLOY_DIR)
	@docker rm securicam-spa-extract >/dev/null
	@rm -rf /tmp/securicam-spa
	@echo "SPA deployed."

# Initial Let's Encrypt issuance via certbot apache plugin.
# Usage: make prod-cert DOMAIN=securicam.example.com EMAIL=you@example.com
prod-cert:
	@if [ -z "$(DOMAIN)" ] || [ -z "$(EMAIL)" ]; then \
		echo "Usage: make prod-cert DOMAIN=xx EMAIL=yy"; exit 1; fi
	sudo certbot --apache -d $(DOMAIN) -m $(EMAIL) --agree-tos --no-eff-email --redirect

prod-renew:
	sudo certbot renew --quiet
	sudo systemctl reload apache2

# ---------------------------------------------------------------- ANDROID ---
.PHONY: apk-debug apk-release adb-install-debug adb-install-release

ANDROID_DOCKER  := docker run --rm \
	-v $(PROJECT_DIR)/android:/project \
	-v $(PROJECT_DIR)/.gradle-cache:/root/.gradle \
	-w /project \
	mingc/android-build-box:latest

apk-debug:
	MSYS_NO_PATHCONV=1 $(ANDROID_DOCKER) bash -c \
		"if [ ! -d /opt/gradle-8.5 ]; then \
			wget -q https://services.gradle.org/distributions/gradle-8.5-bin.zip -O /tmp/g.zip && \
			unzip -q /tmp/g.zip -d /opt; \
		fi && /opt/gradle-8.5/bin/gradle assembleDebug"
	@echo "APK debug : android/app/build/outputs/apk/debug/app-debug.apk"

apk-release:
	@if [ ! -f android/app/keystore/release.keystore ]; then \
		echo "Missing android/app/keystore/release.keystore — run scripts/gen-release-keystore.sh"; exit 1; fi
	MSYS_NO_PATHCONV=1 $(ANDROID_DOCKER) bash -c \
		"if [ ! -d /opt/gradle-8.5 ]; then \
			wget -q https://services.gradle.org/distributions/gradle-8.5-bin.zip -O /tmp/g.zip && \
			unzip -q /tmp/g.zip -d /opt; \
		fi && /opt/gradle-8.5/bin/gradle assembleRelease"
	@echo "APK release : android/app/build/outputs/apk/release/app-release.apk"

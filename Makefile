# Securicam — helper targets for both LAN dev and VPS production modes.
# Usage:
#   make dev-up          # local docker stack (LAN mode)
#   make dev-down
#   make dev-logs SVC=backend
#   make prod-build      # build prod images (requires .env.prod)
#   make prod-up
#   make prod-down
#   make prod-cert DOMAIN=xx EMAIL=yy
#   make prod-renew

DC      := docker compose
DC_PROD := docker compose -f docker-compose.prod.yml --env-file .env.prod

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
.PHONY: prod-build prod-up prod-down prod-logs prod-migrate prod-cert prod-renew prod-key

prod-build:
	$(DC_PROD) build

prod-up:
	$(DC_PROD) up -d

prod-down:
	$(DC_PROD) down

prod-logs:
	$(DC_PROD) logs -f $(SVC)

prod-migrate:
	$(DC_PROD) exec backend php artisan migrate --force

prod-key:
	@echo "Generating Laravel APP_KEY (paste it into .env.prod):"
	@docker run --rm -v "$(PWD)/backend:/app" -w /app composer:2 \
		sh -c "composer install --no-dev --no-interaction --quiet && php artisan key:generate --show"

# Initial Let's Encrypt issuance.
# Usage: make prod-cert DOMAIN=securicam.example.com EMAIL=you@example.com
prod-cert:
	@if [ -z "$(DOMAIN)" ] || [ -z "$(EMAIL)" ]; then \
		echo "Usage: make prod-cert DOMAIN=xx EMAIL=yy" ; exit 1 ; fi
	$(DC_PROD) up -d --build nginx
	$(DC_PROD) run --rm certbot certonly --webroot -w /var/www/certbot \
		-d $(DOMAIN) --email $(EMAIL) --agree-tos --no-eff-email
	$(DC_PROD) exec nginx nginx -s reload

prod-renew:
	$(DC_PROD) run --rm certbot renew
	$(DC_PROD) exec nginx nginx -s reload

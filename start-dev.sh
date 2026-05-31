#!/usr/bin/env bash
# Démarrage backend dony en dev : attend Docker -> stack docker (pg/minio) -> DB healthy -> Spring Boot
set -u
cd "$(dirname "$0")" || exit 1

echo "[start-dev] Attente du daemon Docker..."
for i in $(seq 1 60); do
  if docker info >/dev/null 2>&1; then echo "[start-dev] Docker OK"; break; fi
  sleep 2
done
docker info >/dev/null 2>&1 || { echo "[start-dev] ERREUR: Docker daemon indisponible"; exit 1; }

echo "[start-dev] Chargement .env.dev (secrets Stripe/R2/Google)..."
set -a; source .env.dev; set +a

echo "[start-dev] docker compose up (postgres/minio/adminer/stripe-cli)..."
docker compose -f docker-compose.dev.yml up -d || { echo "[start-dev] ERREUR compose"; exit 1; }

echo "[start-dev] Attente DB dony_db healthy..."
for i in $(seq 1 60); do
  status=$(docker inspect --format '{{.State.Health.Status}}' dony_db 2>/dev/null)
  if [ "$status" = "healthy" ]; then echo "[start-dev] DB healthy"; break; fi
  sleep 2
done

echo "[start-dev] Lancement Spring Boot (profil dev)..."
exec ./mvnw spring-boot:run

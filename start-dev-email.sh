#!/usr/bin/env bash
# Démarrage backend dony en dev avec override email Resend (sandbox sender)
# pour débloquer le login email en dev (domaine yadony.com non vérifié).
set -u
cd "$(dirname "$0")" || exit 1

echo "[start-dev-email] Chargement .env.dev..."
set -a; source .env.dev; set +a

# Override : sender sandbox Resend (autorisé sans domaine vérifié).
export APP_EMAIL_FROM_ADDRESS="onboarding@resend.dev"

echo "[start-dev-email] Lancement Spring Boot (profil dev, from=onboarding@resend.dev)..."
exec ./mvnw spring-boot:run

# Story 1.8 — Configuration Amazon S3 (Backend)

**Date:** 2026-04-19
**Status:** ✅ Complète

## Résumé
Amazon S3 est configuré pour stocker les photos QR de livraison et les photos de profil utilisateur. Stripe Identity gère les documents KYC — rien de KYC ne passe par notre S3.

## Fichiers créés
- `src/main/java/com/dony/api/config/StorageConfig.java` — beans `S3Client` et `S3Presigner` AWS SDK v2
- `src/main/java/com/dony/api/common/StorageService.java` — `uploadFile()`, `generatePresignedUrl()`, `deleteFile()`
- `src/main/java/com/dony/api/common/StorageController.java` — endpoints upload

## Fichiers modifiés
- `src/main/resources/application-dev.yml` — config `aws.s3` (remplace hetzner.s3)
- `src/main/resources/application-prod.yml` — config `aws.s3` (remplace hetzner.s3)

## Endpoints exposés
| Méthode | URL | Usage |
|---------|-----|-------|
| POST | `/api/v1/storage/upload/tracking?bidId={id}` | Photo QR de livraison |
| POST | `/api/v1/storage/upload/profile` | Photo de profil utilisateur |

## Objets stockés dans S3
| Préfixe | Contenu |
|---------|---------|
| `tracking/{bidId}/{timestamp}_{uuid}.jpg` | Photos QR de livraison |
| `users/{uid}/{timestamp}_{uuid}.jpg` | Photos de profil |

## Décisions techniques
- Passage de Hetzner Object Storage à **Amazon S3 natif** — suppression du `endpoint` custom, AWS SDK v2 gère nativement.
- **Stripe Identity gère les documents KYC** → pas de préfixe `kyc/` dans notre S3.
- Préfixes construits **côté serveur** (jamais acceptés depuis le client) pour sécuriser l'upload.
- Validation : taille max 10 MB, types acceptés JPEG/PNG/WebP, préfixes autorisés uniquement.
- URLs signées 1h pour la lecture (jamais d'URL publique directe).

## Variables d'environnement à configurer
```bash
AWS_S3_REGION=eu-west-3        # Paris
AWS_S3_BUCKET=dony-prod
AWS_S3_ACCESS_KEY=AKIA...
AWS_S3_SECRET_KEY=...
```

## Critères d'acceptation couverts
- [x] `uploadFile()` upload dans S3 et retourne la clé
- [x] URL signée valide 1h retournée après upload
- [x] `deleteFile()` supprime l'objet (RGPD)
- [x] Accès direct sans signature impossible (bucket privé par configuration AWS IAM)

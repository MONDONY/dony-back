# Renommage complet de Yadony vers Yadony

## Objectif

Faire de **Yadony** l'unique nom actif du backend et remplacer le domaine
`yadony.com` par `yadony.com`, sans altérer l'historique des migrations Flyway.

## Périmètre

- Remplacer les variantes de marque `Yadony`, `yadony` et `YADONY` par
  `Yadony`, `yadony` et `YADONY`.
- Déplacer les packages Java et les tests de `com.yadony.api` vers
  `com.yadony.api`.
- Renommer les classes techniques portant le préfixe `Yadony`, notamment
  l'application Spring Boot, les propriétés de configuration et les exceptions.
- Renommer les préfixes de propriétés, variables d'environnement, artefacts
  Maven, services et ressources Docker, bases PostgreSQL, buckets et exemples de
  commandes.
- Remplacer `yadony.com` et tous ses sous-domaines par `yadony.com` et les
  sous-domaines correspondants.
- Remplacer le schéma de deep link `yadony://` par `yadony://`.
- Mettre à jour les tests, scripts, fichiers d'environnement et la
  documentation suivis par Git.
- Préserver la modification locale existante de `application-dev.yml`.

## Compatibilité et données existantes

Le renommage est volontairement incompatible avec les anciens noms de
configuration : les déploiements devront fournir les nouvelles variables
`YADONY_*` et utiliser les nouvelles ressources d'infrastructure.

Les migrations Flyway existantes restent strictement inchangées afin de
préserver leurs checksums sur les bases déjà déployées. Les occurrences
historiques de l'ancien nom qui s'y trouvent sont donc la seule exception au
renommage textuel. Si une donnée persistée doit être renommée, elle le sera dans
une nouvelle migration Flyway.

Les références à des ressources externes dont le nouveau nom ne peut pas être
déduit avec certitude, comme une organisation GitHub, seront renommées selon la
forme Yadony et signalées dans le bilan afin que leur existence soit vérifiée
avant déploiement.

## Méthode

1. Inventorier les occurrences et chemins suivis par Git, hors migrations
   Flyway historiques.
2. Effectuer les remplacements en respectant la casse et déplacer les chemins
   Java concernés.
3. Renommer les fichiers et classes dont le nom contient l'ancienne marque.
4. Rechercher les occurrences résiduelles et classifier les exceptions.
5. Compiler le projet et exécuter la suite de tests.
6. Documenter les changements d'infrastructure requis.

## Critères d'acceptation

- Le code principal et les tests utilisent `com.yadony.api`.
- L'application compile avec les classes et propriétés renommées.
- Les domaines actifs utilisent exclusivement `yadony.com`.
- Les fichiers suivis par Git ne contiennent plus l'ancienne marque, sauf dans
  les migrations Flyway historiques ou dans une mention explicitement justifiée.
- Les migrations Flyway déjà présentes n'ont aucun changement.
- La suite de tests Maven réussit.
- Les changements locaux antérieurs de l'utilisateur sont conservés.


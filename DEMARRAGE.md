# Commandes de démarrage — dony

> Toutes les commandes s'exécutent dans **WSL Ubuntu** depuis le répertoire concerné.

---

## 1. Lancer le back (Spring Boot)

### Étape 1 — Démarrer PostgreSQL (Docker)

```bash
cd /mnt/c/Users/abou5/Desktop/mon-dony/dony-back
docker compose -f docker-compose.dev.yml up -d
```

Vérifier que PostgreSQL est prêt :

```bash
docker compose -f docker-compose.dev.yml ps
# STATUS doit afficher "healthy" pour dony_db
```

Interfaces disponibles :
- **Adminer** (UI base de données) → http://localhost:8888
  - Système : PostgreSQL
  - Serveur : `db`
  - Utilisateur : `dony`
  - Mot de passe : `dony_dev_password`
  - Base : `dony_dev`

### Étape 2 — Démarrer Spring Boot

```bash
cd /mnt/c/Users/abou5/Desktop/mon-dony/dony-back
./mvnw spring-boot:run -Dspring.profiles.active=dev
```

Vérifier que le back est actif :

```bash
curl http://localhost:8080/api/v1/actuator/health
# Réponse attendue : {"status":"UP"}
```

### Arrêter le back

```bash
# Arrêter Spring Boot : Ctrl+C dans le terminal

# Arrêter Docker
cd /mnt/c/Users/abou5/Desktop/mon-dony/dony-back
docker compose -f docker-compose.dev.yml down
```

---

## 2. Lancer le front (Flutter)

> **Prérequis :** L'émulateur Android doit être démarré depuis Android Studio sur Windows **avant** d'exécuter ces commandes.

### Étape 1 — Vérifier les devices disponibles

```bash
cd /mnt/c/Users/abou5/Desktop/mon-dony/dony_app
flutter devices
# L'émulateur doit apparaître avec son adresse ex: 172.19.48.1:5555
```

### Étape 2 — Créer le tunnel réseau (WSL2 → émulateur)

> Cette commande est obligatoire à chaque démarrage de l'émulateur pour que le front puisse atteindre le back.

```bash
adb -s 172.19.48.1:5555 reverse tcp:8080 tcp:8080
# Réponse attendue : 8080
```

### Étape 3 — Lancer l'application Flutter

```bash
cd /mnt/c/Users/abou5/Desktop/mon-dony/dony_app
flutter run -d 172.19.48.1:5555 --dart-define-from-file=env.dev.json
```

### Commandes utiles pendant le développement

| Touche | Action |
|--------|--------|
| `r` | Hot reload (rechargement rapide) |
| `R` | Hot restart (redémarrage complet) |
| `q` | Quitter flutter run |
| `d` | Détacher sans fermer l'app |

---

## 3. Ordre de démarrage complet

```
1. Démarrer l'émulateur Android (Windows — Android Studio)
2. docker compose up -d          (WSL — PostgreSQL)
3. ./mvnw spring-boot:run        (WSL — Spring Boot)
4. adb reverse tcp:8080 tcp:8080 (WSL — tunnel réseau)
5. flutter run                   (WSL — Flutter)
```

---

## 4. Vérification que tout est branché

L'écran **Splash** de l'application affiche :
- ✅ **"Serveur connecté"** en vert → tout fonctionne
- ❌ **"Serveur inaccessible"** en rouge → vérifier les étapes 2 et 4

---

## 5. Résolution des problèmes courants

### Docker — erreur de credentials
```bash
echo '{}' > ~/.docker/config.json
```

### Gradle — conflit de verrou
```bash
cd /mnt/c/Users/abou5/Desktop/mon-dony/dony_app
./android/gradlew --stop
```

### Tunnel adb — adresse de l'émulateur différente
```bash
flutter devices
# Récupérer la nouvelle adresse et remplacer 172.19.48.1:5555
adb -s <NOUVELLE_ADRESSE> reverse tcp:8080 tcp:8080
```

### Spring Boot — port 8080 déjà utilisé
```bash
lsof -i :8080
kill -9 <PID>
```

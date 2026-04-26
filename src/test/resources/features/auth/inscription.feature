# language: fr
@smoke @auth
Fonctionnalité: Inscription des utilisateurs
  En tant que nouvel utilisateur
  Je veux m'inscrire sur la plateforme Dony
  Afin de pouvoir envoyer ou transporter des colis

  @happy-path @critical
  Scénario: Inscription réussie en tant qu'expéditeur
    Etant donné un token Firebase pour l'uid "new-sender-001"
    Quand je m'inscris avec le téléphone "+33611000001" et le rôle "SENDER"
    Alors la réponse HTTP est 201
    Et la réponse contient un identifiant utilisateur
    Et la réponse contient le rôle "SENDER"

  @happy-path @critical
  Scénario: Inscription réussie en tant que voyageur
    Etant donné un token Firebase pour l'uid "new-traveler-001"
    Quand je m'inscris avec le téléphone "+33611000002" et le rôle "TRAVELER"
    Alors la réponse HTTP est 201
    Et la réponse contient un identifiant utilisateur
    Et la réponse contient le rôle "TRAVELER"

  @happy-path
  Scénario: Inscription avec les deux rôles expéditeur et voyageur
    Etant donné un token Firebase pour l'uid "new-both-001"
    Quand je m'inscris avec le téléphone "+33611000003" et les rôles "SENDER" et "TRAVELER"
    Alors la réponse HTTP est 201
    Et la réponse contient le rôle "SENDER"
    Et la réponse contient le rôle "TRAVELER"

  @happy-path
  Scénario: Ré-inscription d'un utilisateur existant — idempotent
    Etant donné un token Firebase pour l'uid "existing-user-001"
    Et je m'inscris avec le téléphone "+33611000004" et le rôle "SENDER"
    Quand je m'inscris avec le téléphone "+33611000004" et le rôle "SENDER"
    Alors la réponse HTTP est 201

  @error-case
  Scénario: Tentative d'inscription avec le rôle ADMIN — refusée
    Etant donné un token Firebase pour l'uid "would-be-admin-001"
    Quand je m'inscris avec le téléphone "+33611000005" et le rôle "ADMIN"
    Alors la réponse HTTP est 403
    Et le code d'erreur de la réponse est "forbidden-role"

  @error-case
  Scénario: Inscription avec un numéro de téléphone déjà utilisé
    Etant donné un token Firebase pour l'uid "user-phone-a"
    Et je m'inscris avec le téléphone "+33611000006" et le rôle "SENDER"
    Etant donné un token Firebase pour l'uid "user-phone-b"
    Quand je m'inscris avec le téléphone "+33611000006" et le rôle "TRAVELER"
    Alors la réponse HTTP est 409
    Et le code d'erreur de la réponse est "phone-already-exists"

  @error-case
  Scénario: Inscription sans token Firebase — non autorisé
    Etant donné aucun utilisateur n'est authentifié
    Quand je m'inscris avec le téléphone "+33611000007" et le rôle "SENDER"
    Alors la réponse HTTP est 401

# language: fr
@smoke @auth
Fonctionnalité: Gestion du profil utilisateur
  En tant qu'utilisateur inscrit
  Je veux consulter et modifier mon profil
  Afin de maintenir mes informations à jour

  @happy-path
  Scénario: Consultation du profil d'un utilisateur inscrit
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "profile-sender-001" et le téléphone "+33622000001"
    Quand je consulte mon profil
    Alors la réponse HTTP est 200
    Et la réponse contient un identifiant utilisateur

  @happy-path
  Scénario: Mise à jour du prénom et du nom de famille
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "profile-sender-002" et le téléphone "+33622000002"
    Quand je mets à jour mon profil avec le prénom "Mamadou" et le nom "Diallo"
    Alors la réponse HTTP est 200
    Et le profil contient le prénom "Mamadou" et le nom "Diallo"

  @happy-path
  Scénario: Suppression du compte
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "profile-sender-003" et le téléphone "+33622000003"
    Quand je supprime mon compte
    Alors la réponse HTTP est 204

  @error-case
  Scénario: Consultation du profil sans authentification
    Etant donné aucun utilisateur n'est authentifié
    Quand je consulte mon profil
    Alors la réponse HTTP est 401

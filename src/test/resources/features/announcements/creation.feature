# language: fr
@smoke @announcements
Fonctionnalité: Création d'annonces de voyage
  En tant que voyageur
  Je veux publier des annonces de mes trajets
  Afin de proposer de l'espace bagage aux expéditeurs

  @happy-path @critical
  Scénario: Création réussie d'une annonce de voyage
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-ann-001" et le téléphone "+33633000001"
    Quand je crée une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg
    Alors la réponse HTTP est 201
    Et la réponse contient un identifiant utilisateur
    Et la réponse contient le statut d'annonce "ACTIVE"

  @error-case
  Scénario: Création d'une annonce avec une date dans le passé
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-ann-002" et le téléphone "+33633000002"
    Quand je crée une annonce de "Lyon" à "Abidjan" avec une date dans le passé
    Alors la réponse HTTP est 422

  @error-case
  Scénario: Création d'une annonce sans authentification
    Etant donné aucun utilisateur n'est authentifié
    Quand je crée une annonce de "Marseille" à "Bamako" avec 15 kg disponibles à 4.0 €/kg
    Alors la réponse HTTP est 401

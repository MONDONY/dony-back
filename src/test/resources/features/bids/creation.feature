# language: fr
@smoke @bids
Fonctionnalité: Dépôt d'offres sur les annonces
  En tant qu'expéditeur
  Je veux déposer une offre sur une annonce de voyage
  Afin de proposer mes colis à un voyageur

  @happy-path @critical
  Scénario: Dépôt d'une offre réussie
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-bid-001" et le téléphone "+33666000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-cible"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-bid-001" et le téléphone "+33666000002"
    Quand je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-cible"
    Alors la réponse HTTP est 201
    Et le statut de l'offre est "PENDING"
    Et l'offre "offre-1" est sauvegardée

  @error-case
  Scénario: Valeur déclarée supérieure à 500 € — refusée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-bid-002" et le téléphone "+33666000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-max-val"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-bid-002" et le téléphone "+33666000004"
    Quand je dépose une offre avec une valeur déclarée de 501.0 € sur l'annonce "annonce-max-val"
    Alors la réponse HTTP est 422

  @error-case
  Scénario: Disclaimer non accepté — refusé
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-bid-003" et le téléphone "+33666000005"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-disclaimer"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-bid-003" et le téléphone "+33666000006"
    Quand je dépose une offre sans accepter le disclaimer sur l'annonce "annonce-disclaimer"
    Alors la réponse HTTP est 422

  @error-case
  Scénario: Double offre sur la même annonce — refusée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-bid-004" et le téléphone "+33666000007"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-dup"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-bid-004" et le téléphone "+33666000008"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-dup"
    Quand je dépose une offre de 3.0 kg à 30.0 € sur l'annonce "annonce-dup"
    Alors la réponse HTTP est 409

  @error-case
  Scénario: Offre sans authentification — refusée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-bid-005" et le téléphone "+33666000009"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-noauth"
    Etant donné aucun utilisateur n'est authentifié
    Quand je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-noauth"
    Alors la réponse HTTP est 401

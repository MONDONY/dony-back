# language: fr
@regression @payments
Fonctionnalité: Gestion des paiements en escrow
  En tant qu'expéditeur ou voyageur
  Je veux gérer les paiements sécurisés liés au transport
  Afin de protéger les deux parties jusqu'à la livraison confirmée

  @happy-path
  Scénario: Consultation du statut de paiement d'une offre sans paiement
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-pay-001" et le téléphone "+33699000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-pay-1"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-pay-001" et le téléphone "+33699000002"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-pay-1"
    Et l'offre "offre-pay-1" est sauvegardée
    Quand je demande le statut de paiement de l'offre "offre-pay-1"
    Alors la réponse HTTP est 404

  @error-case
  Scénario: Tentative de paiement pour une offre non acceptée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-pay-002" et le téléphone "+33699000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-pay-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-pay-002" et le téléphone "+33699000004"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-pay-2"
    Et l'offre "offre-pay-2" est sauvegardée
    Quand je tente de créer un paiement pour l'offre "offre-pay-2"
    Alors la réponse HTTP est 422

  @error-case
  Scénario: Webhook Stripe avec signature invalide
    Etant donné aucun utilisateur n'est authentifié
    Quand j'envoie un webhook Stripe avec une signature invalide
    Alors la réponse HTTP est 400
    Et le code d'erreur de la réponse est "invalid-webhook-signature"

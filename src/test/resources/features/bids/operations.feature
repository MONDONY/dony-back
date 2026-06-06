# language: fr
@regression @bids
Fonctionnalité: Opérations secondaires sur les offres
  En tant qu'expéditeur ou voyageur
  Je veux estimer, consulter et gérer le cycle opérationnel d'une offre
  Afin de couvrir tout le parcours au-delà de l'acceptation

  @happy-path
  Scénario: Devis avant le dépôt d'une offre
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "ops-traveler-001" et le téléphone "+33677100001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-quote"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ops-sender-001" et le téléphone "+33677100002"
    Quand je demande un devis pour 5.0 kg sur l'annonce "annonce-quote"
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Confirmation de présence puis consultation du détail de l'offre
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "ops-traveler-002" et le téléphone "+33677100003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-ops-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ops-sender-002" et le téléphone "+33677100004"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-ops-2"
    Et l'offre "offre-ops-2" est sauvegardée
    Et le paiement de l'offre "offre-ops-2" est validé
    Etant donné l'utilisateur "ops-traveler-002" est authentifié en tant que VOYAGEUR
    Et j'accepte l'offre "offre-ops-2"
    Quand le voyageur confirme sa présence pour l'offre "offre-ops-2"
    Alors la réponse HTTP est 200
    Quand je consulte le détail de l'offre "offre-ops-2"
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Le voyageur refuse le colis à la remise
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "ops-traveler-003" et le téléphone "+33677100005"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-ops-3"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ops-sender-003" et le téléphone "+33677100006"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-ops-3"
    Et l'offre "offre-ops-3" est sauvegardée
    Et le paiement de l'offre "offre-ops-3" est validé
    Etant donné l'utilisateur "ops-traveler-003" est authentifié en tant que VOYAGEUR
    Et j'accepte l'offre "offre-ops-3"
    Quand le voyageur refuse le colis de l'offre "offre-ops-3"
    Alors la réponse HTTP est 200
    Et le statut de l'offre est "PARCEL_REFUSED"

  @happy-path
  Scénario: Masquer une offre côté expéditeur puis l'écarter côté voyageur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "ops-traveler-004" et le téléphone "+33677100007"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-ops-4"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "ops-sender-004" et le téléphone "+33677100008"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-ops-4"
    Et l'offre "offre-ops-4" est sauvegardée
    Quand je masque l'offre "offre-ops-4"
    Alors la réponse HTTP est 204
    Quand l'expéditeur annule l'offre "offre-ops-4"
    Alors la réponse HTTP est 200
    Et le statut de l'offre est "CANCELLED"
    Etant donné l'utilisateur "ops-traveler-004" est authentifié en tant que VOYAGEUR
    Quand le voyageur écarte l'offre "offre-ops-4"
    Alors la réponse HTTP est 204

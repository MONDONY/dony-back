# language: fr
@regression @cancellation
Fonctionnalité: Annulation d'un voyage
  En tant que voyageur
  Je veux pouvoir annuler un voyage
  Et permettre aux expéditeurs affectés de trouver une alternative

  @happy-path @critical
  Scénario: Annulation d'un voyage sans offres acceptées
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-001" et le téléphone "+33700000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-cancel-1"
    Quand j'annule l'annonce "annonce-cancel-1" avec la raison "Voyage annulé pour raisons personnelles"
    Alors la réponse HTTP est 201
    Et la réponse indique que 0 offre(s) ont été annulée(s)

  @happy-path @critical
  Scénario: Annulation avec offres acceptées — cascade vers les offres
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-002" et le téléphone "+33700000002"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 20 kg disponibles à 6.0 €/kg sauvegardée sous "annonce-cancel-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-cancel-002" et le téléphone "+33700000003"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-cancel-2"
    Et l'offre "offre-cancel-2" est sauvegardée
    Etant donné l'utilisateur "traveler-cancel-002" est authentifié en tant que VOYAGEUR
    Et j'accepte l'offre "offre-cancel-2"
    Quand j'annule l'annonce "annonce-cancel-2" avec la raison "Problème médical urgent"
    Alors la réponse HTTP est 201
    Et la réponse indique que 1 offre(s) ont été annulée(s)

  @error-case
  Scénario: Annulation d'une annonce déjà annulée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-003" et le téléphone "+33700000004"
    Et il existe une annonce de "Marseille" à "Bamako" avec 15 kg disponibles à 4.5 €/kg sauvegardée sous "annonce-cancel-3"
    Et j'annule l'annonce "annonce-cancel-3" avec la raison "Premier motif"
    Quand j'annule l'annonce "annonce-cancel-3" avec la raison "Second motif"
    Alors la réponse HTTP est 409
    Et le code d'erreur de la réponse est "already-cancelled"

  @error-case
  Scénario: Annulation d'une annonce appartenant à un autre voyageur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-cancel-004" et le téléphone "+33700000005"
    Et il existe une annonce de "Paris" à "Douala" avec 10 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-cancel-4"
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "intruder-001" et le téléphone "+33700000099"
    Quand j'annule l'annonce "annonce-cancel-4" avec la raison "Je veux annuler l'annonce d'un autre"
    Alors la réponse HTTP est 403
    Et le code d'erreur de la réponse est "forbidden"

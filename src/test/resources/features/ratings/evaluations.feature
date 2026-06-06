# language: fr
@regression @ratings
Fonctionnalité: Évaluations après livraison
  En tant qu'expéditeur ou voyageur
  Je veux évaluer mon interlocuteur après une livraison réussie
  Afin de construire la confiance sur la plateforme

  @happy-path @critical
  Scénario: Évaluations croisées après une livraison complétée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "rate-traveler-001" et le téléphone "+33699100001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-rate"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "rate-sender-001" et le téléphone "+33699100002"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-rate"
    Et l'offre "offre-rate" est sauvegardée
    Et le paiement de l'offre "offre-rate" est validé
    Etant donné l'utilisateur "rate-traveler-001" est authentifié en tant que VOYAGEUR
    Et j'accepte l'offre "offre-rate"
    Et l'offre "offre-rate" est marquée comme livrée
    Et le jeton de suivi de l'offre "offre-rate" est mémorisé
    Et l'identifiant du compte "rate-traveler-001" est sauvegardé sous "traveler-acct"
    Et l'identifiant du compte "rate-sender-001" est sauvegardé sous "sender-acct"
    Etant donné l'utilisateur "rate-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je consulte mon évaluation en attente
    Alors la réponse HTTP est 200
    Quand j'évalue le voyageur sur l'offre "offre-rate" avec 5 étoiles
    Alors la réponse HTTP est 201
    Et le champ "stars" de la réponse vaut "5"
    Etant donné l'utilisateur "rate-traveler-001" est authentifié en tant que VOYAGEUR
    Quand j'évalue l'expéditeur sur l'offre "offre-rate" avec 4 étoiles
    Alors la réponse HTTP est 201
    Quand je consulte mes évaluations reçues
    Alors la réponse HTTP est 200
    Et la réponse contient le champ "averageRating"
    Quand je consulte les évaluations publiques du compte "traveler-acct"
    Alors la réponse HTTP est 200
    Et la réponse contient le champ "averageRating"
    Quand le destinataire évalue avec 5 étoiles via le lien de suivi
    Alors la réponse HTTP est 201

  @error-case
  Scénario: Impossible d'évaluer une offre non complétée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "rate-traveler-002" et le téléphone "+33699100003"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-rate-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "rate-sender-002" et le téléphone "+33699100004"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-rate-2"
    Et l'offre "offre-rate-2" est sauvegardée
    Quand j'évalue le voyageur sur l'offre "offre-rate-2" avec 5 étoiles
    Alors la réponse HTTP est 422

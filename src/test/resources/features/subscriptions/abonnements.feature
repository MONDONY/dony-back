# language: fr
@regression @subscriptions
Fonctionnalité: Abonnements aux voyageurs
  En tant qu'expéditeur
  Je veux m'abonner à un voyageur
  Afin d'être notifié de ses nouvelles annonces

  @happy-path
  Scénario: Cycle de vie complet d'un abonnement
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "sub-traveler-001" et le téléphone "+33699200001"
    Et l'identifiant du compte "sub-traveler-001" est sauvegardé sous "trav-acct"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sub-sender-001" et le téléphone "+33699200002"
    Etant donné l'utilisateur "sub-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je m'abonne au voyageur "trav-acct"
    Alors la réponse HTTP est 201
    Quand je consulte mon abonnement au voyageur "trav-acct"
    Alors la réponse HTTP est 200
    Et le champ "subscribed" de la réponse est vrai
    Quand je désactive les notifications push de l'abonnement au voyageur "trav-acct"
    Alors la réponse HTTP est 200
    Quand je consulte mes abonnements
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je marque comme vu l'abonnement au voyageur "trav-acct"
    Alors la réponse HTTP est 204
    Etant donné l'utilisateur "sub-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je consulte mes abonnés
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Etant donné l'utilisateur "sub-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je me désabonne du voyageur "trav-acct"
    Alors la réponse HTTP est 204

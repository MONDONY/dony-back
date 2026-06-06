# language: fr
@regression @addressbook
Fonctionnalité: Carnet d'adresses
  En tant qu'utilisateur
  Je veux gérer mes adresses de retrait, de livraison et mes destinataires
  Afin de réutiliser mes informations lors de mes envois

  @happy-path
  Scénario: Cycle de vie complet d'une adresse de retrait
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "addr-sender-001" et le téléphone "+33699000001"
    Etant donné l'utilisateur "addr-sender-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une adresse de retrait "Maison" sauvegardée sous "pickup-1"
    Alors la réponse HTTP est 201
    Quand je consulte mes adresses de retrait
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je modifie l'adresse de retrait "pickup-1" avec le libellé "Bureau"
    Alors la réponse HTTP est 200
    Et le champ "label" de la réponse vaut "Bureau"
    Quand je définis l'adresse de retrait "pickup-1" comme adresse par défaut
    Alors la réponse HTTP est 200
    Quand je supprime l'adresse de retrait "pickup-1"
    Alors la réponse HTTP est 204
    Quand je consulte mes adresses de retrait
    Alors la réponse est une liste de 0 élément

  @happy-path
  Scénario: Gestion des adresses de livraison
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "addr-sender-002" et le téléphone "+33699000002"
    Etant donné l'utilisateur "addr-sender-002" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée une adresse de livraison "Domicile Dakar" sauvegardée sous "delivery-1"
    Alors la réponse HTTP est 201
    Quand je consulte mes adresses de livraison
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je supprime l'adresse de livraison "delivery-1"
    Alors la réponse HTTP est 204

  @happy-path
  Scénario: Cycle de vie complet d'un destinataire
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "addr-sender-003" et le téléphone "+33699000003"
    Etant donné l'utilisateur "addr-sender-003" est authentifié en tant qu'EXPÉDITEUR
    Quand je crée un destinataire "Aïssatou Ba" sauvegardé sous "recipient-1"
    Alors la réponse HTTP est 201
    Quand je consulte mes destinataires
    Alors la réponse HTTP est 200
    Et la réponse est une liste de 1 élément
    Quand je modifie le destinataire "recipient-1" avec le nom "Moussa Traoré"
    Alors la réponse HTTP est 200
    Et le champ "fullName" de la réponse vaut "Moussa Traoré"
    Quand je supprime le destinataire "recipient-1"
    Alors la réponse HTTP est 204

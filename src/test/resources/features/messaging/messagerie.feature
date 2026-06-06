# language: fr
@regression @messaging
Fonctionnalité: Messagerie entre expéditeur et voyageur
  En tant qu'expéditeur ou voyageur
  Je veux pouvoir consulter mes conversations
  Afin d'échanger après l'acceptation d'une offre

  @happy-path @critical
  Scénario: L'expéditeur peut lister ses conversations après acceptation d'une offre
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-msg-001" et le téléphone "+33677100001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-msg-1"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-msg-001" et le téléphone "+33677100002"
    Et je dépose une offre de 5.0 kg à 50.0 € sur l'annonce "annonce-msg-1"
    Et l'offre "offre-msg-1" est sauvegardée
    Etant donné l'utilisateur "traveler-msg-001" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-msg-1" est validé
    Et j'accepte l'offre "offre-msg-1"
    Etant donné l'utilisateur "sender-msg-001" est authentifié en tant qu'EXPÉDITEUR
    Quand j'attends que la conversation soit créée
    Et je consulte mes conversations
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 1 conversation
    Et je sauvegarde l'identifiant de la première conversation sous "conv-msg-1"

  @security
  Scénario: Un intrus ne peut pas accéder à la conversation d'un autre utilisateur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-msg-002" et le téléphone "+33677100003"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 15 kg disponibles à 6.0 €/kg sauvegardée sous "annonce-msg-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-msg-002" et le téléphone "+33677100004"
    Et je dépose une offre de 3.0 kg à 30.0 € sur l'annonce "annonce-msg-2"
    Et l'offre "offre-msg-2" est sauvegardée
    Etant donné l'utilisateur "traveler-msg-002" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-msg-2" est validé
    Et j'accepte l'offre "offre-msg-2"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "intruder-msg-001" et le téléphone "+33677100005"
    Quand j'attends que la conversation soit créée
    Et l'intrus accède à la conversation de l'annonce "annonce-msg-2"
    Alors la réponse HTTP est 403

  @security
  Scénario: Un intrus ne peut pas uploader une image dans la conversation d'un autre utilisateur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-msg-003" et le téléphone "+33677100006"
    Et il existe une annonce de "Marseille" à "Bamako" avec 10 kg disponibles à 7.0 €/kg sauvegardée sous "annonce-msg-3"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "sender-msg-003" et le téléphone "+33677100007"
    Et je dépose une offre de 4.0 kg à 40.0 € sur l'annonce "annonce-msg-3"
    Et l'offre "offre-msg-3" est sauvegardée
    Etant donné l'utilisateur "traveler-msg-003" est authentifié en tant que VOYAGEUR
    Et le paiement de l'offre "offre-msg-3" est validé
    Et j'accepte l'offre "offre-msg-3"
    Etant donné un utilisateur EXPÉDITEUR enregistré avec l'uid "intruder-msg-002" et le téléphone "+33677100008"
    Quand j'attends que la conversation soit créée
    Et l'intrus tente d'uploader une image dans la conversation de l'annonce "annonce-msg-3"
    Alors la réponse HTTP est 403

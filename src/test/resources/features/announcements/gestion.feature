# language: fr
@regression @announcements
Fonctionnalité: Gestion des annonces de voyage
  En tant que voyageur
  Je veux gérer mes annonces publiées
  Afin de les mettre à jour ou les supprimer si nécessaire

  @happy-path
  Scénario: Consultation de mes propres annonces
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-mgmt-001" et le téléphone "+33655000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "ma-annonce-1"
    Et il existe une annonce de "Paris" à "Abidjan" avec 10 kg disponibles à 6.0 €/kg sauvegardée sous "ma-annonce-2"
    Quand je consulte mes annonces
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 2 annonces

  @happy-path
  Scénario: Consultation du détail d'une annonce
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-mgmt-002" et le téléphone "+33655000002"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-detail"
    Quand je consulte le détail de l'annonce "annonce-detail"
    Alors la réponse HTTP est 200
    Et la réponse contient le statut d'annonce "ACTIVE"

  @happy-path
  Scénario: Modification d'une annonce active
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-mgmt-003" et le téléphone "+33655000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-to-update"
    Quand je modifie l'annonce "annonce-to-update" pour mettre 25 kg disponibles
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Suppression d'une annonce active
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-mgmt-004" et le téléphone "+33655000004"
    Et il existe une annonce de "Lyon" à "Bamako" avec 15 kg disponibles à 4.5 €/kg sauvegardée sous "annonce-to-delete"
    Quand je supprime l'annonce "annonce-to-delete"
    Alors la réponse HTTP est 204

  @error-case
  Scénario: Modification d'une annonce appartenant à un autre voyageur
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-owner-001" et le téléphone "+33655000005"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-autre"
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-other-001" et le téléphone "+33655000099"
    Quand je modifie l'annonce "annonce-autre" pour mettre 5 kg disponibles
    Alors la réponse HTTP est 403

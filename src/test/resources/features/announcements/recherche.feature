# language: fr
@regression @announcements
Fonctionnalité: Recherche d'annonces de voyage
  En tant qu'expéditeur
  Je veux rechercher des annonces disponibles
  Afin de trouver un voyageur pour transporter mon colis

  @happy-path
  Scénario: Recherche de toutes les annonces disponibles
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-001" et le téléphone "+33644000001"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-paris-dakar"
    Et un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-002" et le téléphone "+33644000002"
    Et il existe une annonce de "Lyon" à "Abidjan" avec 15 kg disponibles à 6.0 €/kg sauvegardée sous "annonce-lyon-abidjan"
    Quand je recherche toutes les annonces
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 2 annonces

  @happy-path
  Scénario: Filtrage par ville de départ
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-003" et le téléphone "+33644000003"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-1"
    Et un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-004" et le téléphone "+33644000004"
    Et il existe une annonce de "Lyon" à "Dakar" avec 10 kg disponibles à 7.0 €/kg sauvegardée sous "annonce-2"
    Quand je recherche les annonces au départ de "Paris"
    Alors la réponse HTTP est 200
    Et la réponse contient 1 annonces
    Et toutes les annonces partent de "Paris"

  @happy-path
  Scénario: Filtrage par ville d'arrivée
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-005" et le téléphone "+33644000005"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-3"
    Quand je recherche les annonces à destination de "Dakar"
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 1 annonces

  @happy-path
  Scénario: Résultats vides quand aucune annonce ne correspond
    Etant donné l'utilisateur "searcher-empty-001" est authentifié en tant qu'EXPÉDITEUR
    Quand je recherche les annonces au départ de "Ville-Inexistante"
    Alors la réponse HTTP est 200
    Et la réponse contient 0 annonces

  @happy-path
  Scénario: Filtrage par prix maximum au kg
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-010" et le téléphone "+33644000010"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 4.0 €/kg sauvegardée sous "annonce-pas-chere"
    Et un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-011" et le téléphone "+33644000011"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 9.0 €/kg sauvegardée sous "annonce-chere"
    Quand je recherche les annonces avec un prix maximum de 5.0 euros par kg
    Alors la réponse HTTP est 200
    Et la réponse contient 1 annonces

  @happy-path
  Scénario: Filtrage par capacité minimale disponible
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-012" et le téléphone "+33644000012"
    Et il existe une annonce de "Paris" à "Dakar" avec 25 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-grande"
    Et un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-013" et le téléphone "+33644000013"
    Et il existe une annonce de "Paris" à "Dakar" avec 5 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-petite"
    Quand je recherche les annonces avec au moins 20 kg disponibles
    Alors la réponse HTTP est 200
    Et la réponse contient 1 annonces

  @happy-path
  Scénario: Tri des annonces par prix croissant
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-014" et le téléphone "+33644000014"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 8.0 €/kg sauvegardée sous "annonce-tri-1"
    Et un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-015" et le téléphone "+33644000015"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 3.0 €/kg sauvegardée sous "annonce-tri-2"
    Quand je recherche les annonces triées par prix croissant
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 2 annonces

  @happy-path
  Scénario: Filtrage par mode de transport
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-016" et le téléphone "+33644000016"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-avion"
    Quand je recherche les annonces en mode de transport "PLANE"
    Alors la réponse HTTP est 200
    Et la réponse contient au moins 1 annonces

  @happy-path
  Scénario: Filtrage sur les voyageurs vérifiés KYC
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-017" et le téléphone "+33644000017"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-kyc"
    Quand je recherche les annonces des voyageurs vérifiés KYC
    Alors la réponse HTTP est 200

  @happy-path
  Scénario: Consultation de mes corridors de voyage
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "traveler-search-018" et le téléphone "+33644000018"
    Et il existe une annonce de "Paris" à "Dakar" avec 20 kg disponibles à 5.0 €/kg sauvegardée sous "annonce-corridor"
    Etant donné l'utilisateur "traveler-search-018" est authentifié en tant que VOYAGEUR
    Quand je consulte mes corridors
    Alors la réponse HTTP est 200

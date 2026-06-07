# language: fr
@regression @pro
Fonctionnalité: Espace PRO du voyageur
  En tant que voyageur PRO
  Je veux consulter mes analytics, statistiques et exports fiscaux
  Afin de piloter mon activité de transport

  @happy-path
  Scénario: Consultation des analytics, statistiques et calendrier
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "pro-traveler-001" et le téléphone "+33699600001"
    Et le compte "pro-traveler-001" est un compte PRO
    Etant donné l'utilisateur "pro-traveler-001" est authentifié en tant que VOYAGEUR
    Quand je consulte mes analytics avec la période "month"
    Alors la réponse HTTP est 200
    Quand je consulte mes analytics avec la période "quarter"
    Alors la réponse HTTP est 200
    Quand je consulte mes analytics avec la période "year"
    Alors la réponse HTTP est 200
    Quand je consulte mes statistiques de voyageur
    Alors la réponse HTTP est 200
    Quand je consulte mon calendrier de voyageur
    Alors la réponse HTTP est 200

  @error-case
  Scénario: Période d'analytics invalide
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "pro-traveler-002" et le téléphone "+33699600002"
    Et le compte "pro-traveler-002" est un compte PRO
    Etant donné l'utilisateur "pro-traveler-002" est authentifié en tant que VOYAGEUR
    Quand je consulte mes analytics avec la période "invalide"
    Alors la réponse HTTP est 400

  @happy-path
  Scénario: Exports fiscaux multi-formats et multi-types
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "pro-traveler-003" et le téléphone "+33699600003"
    Et le compte "pro-traveler-003" est un compte PRO
    Etant donné l'utilisateur "pro-traveler-003" est authentifié en tant que VOYAGEUR
    Quand j'exporte mes données fiscales pour l'année 2025 au format "csv"
    Alors la réponse HTTP est 200
    Quand j'exporte mes données fiscales pour l'année 2025 au format "pdf" et le type "summary"
    Alors la réponse HTTP est 200
    Quand j'exporte mes données fiscales pour l'année 2025 au format "csv" et le type "dac7"
    Alors la réponse HTTP est 200
    Quand j'exporte mes données fiscales pour l'année 2025 au format "csv" et le type "summary"
    Alors la réponse HTTP est 200

  @error-case
  Scénario: Export fiscal avec un format invalide
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "pro-traveler-005" et le téléphone "+33699600005"
    Et le compte "pro-traveler-005" est un compte PRO
    Etant donné l'utilisateur "pro-traveler-005" est authentifié en tant que VOYAGEUR
    Quand j'exporte mes données fiscales pour l'année 2025 au format "xml"
    Alors la réponse HTTP est 400

  @error-case
  Scénario: Export fiscal pour une année invalide
    Etant donné un utilisateur VOYAGEUR enregistré avec l'uid "pro-traveler-004" et le téléphone "+33699600004"
    Et le compte "pro-traveler-004" est un compte PRO
    Etant donné l'utilisateur "pro-traveler-004" est authentifié en tant que VOYAGEUR
    Quand j'exporte mes données fiscales pour l'année 2019 au format "csv"
    Alors la réponse HTTP est 400

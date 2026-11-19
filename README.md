# PikaChecker

Mod client **Minecraft 1.8.9** pour **Fabric 1.8.9** conçu pour vérifier des informations de bannissement / punitions et interagir avec l'affichage joueur en jeu.

## Fonctionnalités

- **Vérification des avertissements et punitions**
- **Intégrations client :**
  - Commandes `/pcheck` afin de modifier les paramètres du mod ou de check manuellement un joueur
  - Overlay dans le tab à l'aide d'un `!` (Orange: Le joueur a été ban pour insultes ou similaire; Rouge: Le joueur a été banni pour avoir utilisé des modifications clients ou cheats.

- **Configuration :** gestion des paramètres via la commande /pcheck ou OneConfig (Utilisé dans OneClient, un launcher Minecraft développé par Polyfrost).

## Prérequis

- **Minecraft :** `1.8.9`
- **Modloader :** Fabric (Ornithe 1.8.9 et testé sur le Fabric Mod Loader 0.20.1)
- **Java :** Java 25 afin que Ornithe fonctionne sur Fabric 1.8.9

## Installation

1. Installe **Fabric/Ornithe** pour **Minecraft 1.8.9**.
2. Place le mod `.jar` compilé de **PikaChecker** dans ton dossier `.minecraft/mods/` ou fais un déplacer/déposer sur le Launcher OneClient.
3. Lance le jeu et ajuste la configuration si nécessaire.

## Compilation depuis les sources

```bash
./gradlew build

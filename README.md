# PikaChecker

Mod client **Minecraft 1.8.9** pour **Fabric** (`fr.loumox85.pikacheck`) conçu pour vérifier des informations de bannissement / punitions et interagir avec l'affichage joueur en jeu.

## Fonctionnalités

- **Vérification & punitions :** logique principale portée par `BanChecker` et `PikaPunishments`.
- **Intégration client (Mixins) :**
  - `LocalClientPlayerEntityMixin` : hook côté joueur local (ex. commandes, déclencheurs ou messages).
  - `PlayerTabOverlayMixin` : personnalisation ou enrichissement de l'overlay `Tab`[cite: 1].
- **Configuration :** gestion des paramètres via `PikaCheckConfig`[cite: 1].

## Prérequis

- **Minecraft :** `1.8.9`[cite: 1]
- **Modloader :** Fabric (voir `build.gradle` et `fabric.mod.json` pour les versions exactes de Fabric Loader / mappings)[cite: 1]
- **Java :** version configurée dans `build.gradle`[cite: 1]

## Installation

1. Installe **Fabric** pour **Minecraft 1.8.9**[cite: 1].
2. Place le `.jar` compilé de **PikaChecker** dans ton dossier `.minecraft/mods/`.
3. Lance le jeu et ajuste la configuration si nécessaire (`PikaCheckConfig`)[cite: 1].

## Compilation depuis les sources

```bash
./gradlew build

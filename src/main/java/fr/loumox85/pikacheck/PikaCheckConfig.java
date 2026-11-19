package fr.loumox85.pikacheck;

import net.ornithemc.osl.config.api.ConfigManager;
import net.ornithemc.osl.config.api.ConfigScope;
import net.ornithemc.osl.config.api.LoadingPhase;
import net.ornithemc.osl.config.api.config.BaseConfig;
import net.ornithemc.osl.config.api.config.option.BooleanOption;
import net.ornithemc.osl.config.api.config.option.IntegerOption;
import net.ornithemc.osl.config.api.config.option.validator.IntegerValidators;
import net.ornithemc.osl.config.api.serdes.FileSerializerType;
import net.ornithemc.osl.config.api.serdes.SerializerTypes;

/**
 * Réglages du mod. C'est une config OSL : OneConfig la détecte toute seule et l'affiche
 * dans son menu (page "PikaCheck"). Fichier : config/pikacheck/pikacheck.json
 *
 * Les noms d'options restent sans accents : ils servent aussi de clés dans le fichier JSON.
 */
public class PikaCheckConfig extends BaseConfig {

	public static final PikaCheckConfig INSTANCE = new PikaCheckConfig();

	public static final BooleanOption AUTO = new BooleanOption(
		"Icones tab + recherche auto",
		"Affiche un ! dans le tab devant les joueurs sanctionnés et cherche leur historique tout seul (sur Pika uniquement).",
		true);

	public static final BooleanOption ALERTS = new BooleanOption(
		"Alertes cheat dans le chat",
		"Envoie un message dans le chat quand un joueur affiché a un ban de type cheat (une fois par joueur et par session).",
		true);

	public static final BooleanOption ALERT_ALL = new BooleanOption(
		"Alertes pour tous les bans",
		"Désactivé (par défaut) : alerte seulement pour les ! rouges (ban de type cheat). Activé : aussi pour les ! orange (tout autre ban).",
		false);

	public static final IntegerOption REALERT_MINUTES = new IntegerOption(
		"Re-alerte apres (min)",
		"Un joueur déjà signalé est signalé de nouveau seulement si tu ne l'as pas vu pendant au moins ce nombre de minutes.",
		3,
		IntegerValidators.minmax(1, 30));

	public static final IntegerOption MAX_PER_HOUR = new IntegerOption(
		"Recherches auto max par heure",
		"Plafond de recherches automatiques sur 1 heure glissante. Plus bas = plus prudent vis-à-vis de Pika.",
		200,
		IntegerValidators.minmax(10, 500));

	public static final IntegerOption DELAY_SECONDS = new IntegerOption(
		"Delai entre requetes (s)",
		"Délai minimum entre deux requêtes vers Pika. Ne peut pas descendre sous 3 s pour éviter de surcharger leur site.",
		3,
		IntegerValidators.minmax(3, 30));

	@Override
	public String getNamespace() {
		return "pikacheck";
	}

	@Override
	public String getName() {
		return "PikaCheck";
	}

	@Override
	public String getSaveName() {
		return "pikacheck.json";
	}

	@Override
	public ConfigScope getScope() {
		return ConfigScope.GLOBAL;
	}

	@Override
	public LoadingPhase getLoadingPhase() {
		return LoadingPhase.START;
	}

	@Override
	public FileSerializerType<?> getType() {
		return SerializerTypes.JSON;
	}

	@Override
	public int getVersion() {
		return 0;
	}

	@Override
	public void init() {
		registerOptions("General", AUTO, ALERTS, ALERT_ALL, REALERT_MINUTES, MAX_PER_HOUR, DELAY_SECONDS);
	}

	/**
	 * Fait le lien entre la config OSL et BanChecker. Chaque lecture est protégée :
	 * tant que la config n'est pas chargée, OSL refuse l'accès, et on retombe sur les valeurs par défaut.
	 */
	public static final class Bridge implements BanChecker.Settings {

		@Override
		public boolean auto() {
			try {
				return AUTO.get();
			} catch (RuntimeException e) {
				return true;
			}
		}

		@Override
		public boolean alerts() {
			try {
				return ALERTS.get();
			} catch (RuntimeException e) {
				return true;
			}
		}

		@Override
		public boolean alertAll() {
			try {
				return ALERT_ALL.get();
			} catch (RuntimeException e) {
				return false;
			}
		}

		@Override
		public int realertMinutes() {
			try {
				return REALERT_MINUTES.get();
			} catch (RuntimeException e) {
				return 3;
			}
		}

		@Override
		public int maxAutoPerHour() {
			try {
				return MAX_PER_HOUR.get();
			} catch (RuntimeException e) {
				return 200;
			}
		}

		@Override
		public long minIntervalMs() {
			try {
				return Math.max(3, DELAY_SECONDS.get()) * 1000L; // jamais sous 3 s
			} catch (RuntimeException e) {
				return 3000L;
			}
		}

		@Override
		public void setAuto(boolean value) {
			try {
				AUTO.set(value);
				ConfigManager.save(INSTANCE);
			} catch (RuntimeException ignored) {
				// config pas encore chargée : le changement est ignoré
			}
		}

		@Override
		public void setAlerts(boolean value) {
			try {
				ALERTS.set(value);
				ConfigManager.save(INSTANCE);
			} catch (RuntimeException ignored) {
				// config pas encore chargée : le changement est ignoré
			}
		}

		@Override
		public void setAlertAll(boolean value) {
			try {
				ALERT_ALL.set(value);
				ConfigManager.save(INSTANCE);
			} catch (RuntimeException ignored) {
				// config pas encore chargée : le changement est ignoré
			}
		}
	}
}

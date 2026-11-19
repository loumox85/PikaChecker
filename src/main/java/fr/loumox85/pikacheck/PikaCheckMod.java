package fr.loumox85.pikacheck;

import net.ornithemc.osl.config.api.ConfigManager;
import net.ornithemc.osl.entrypoints.api.ModInitializer;

/** Point d'entrée de PikaCheck : enregistre la config (menu OneConfig) et la branche sur BanChecker. */
public class PikaCheckMod implements ModInitializer {

	@Override
	public void init() {
		ConfigManager.register(PikaCheckConfig.INSTANCE);
		BanChecker.setSettings(new PikaCheckConfig.Bridge());
	}
}

package fr.loumox85.pikacheck.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.loumox85.pikacheck.BanChecker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.text.LiteralText;

@Mixin(LocalClientPlayerEntity.class)
public class LocalClientPlayerEntityMixin {

	private static final String COMMAND = "/pcheck";
	private static final int SCAN_EVERY_TICKS = 10; // 2 fois par seconde

	@Unique
	private int pikacheck$ticks = 0;

	/**
	 * Intercepte les commandes "/pcheck ..." avant qu'elles partent au serveur :
	 *   /pcheck <pseudo>      détail des sanctions d'un joueur
	 *   /pcheck auto on|off   active / coupe les icônes automatiques du tab
	 *   /pcheck alert on|off  active / coupe les alertes de cheateurs dans le chat
	 *   /pcheck alert all on|off  alerte aussi pour les ! orange (tout ban), pas seulement les ! rouges
	 *   /pcheck status        diagnostic (nombre de requêtes, pause éventuelle)
	 * Tout le reste du chat passe normalement.
	 */
	@Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
	private void pikacheck$onSendChat(String message, CallbackInfo ci) {
		String trimmed = message.trim();
		if (!trimmed.equalsIgnoreCase(COMMAND) && !trimmed.toLowerCase().startsWith(COMMAND + " ")) {
			return;
		}
		ci.cancel();

		LocalClientPlayerEntity self = (LocalClientPlayerEntity) (Object) this;
		String[] parts = trimmed.split("\\s+");

		if (parts.length == 2 && parts[1].equalsIgnoreCase("status")) {
			self.addMessage(new LiteralText(BanChecker.statusLine()));
			return;
		}

		if (parts.length == 4 && parts[1].equalsIgnoreCase("alert") && parts[2].equalsIgnoreCase("all")
			&& (parts[3].equalsIgnoreCase("on") || parts[3].equalsIgnoreCase("off"))) {
			boolean on = parts[3].equalsIgnoreCase("on");
			BanChecker.setAlertAllEnabled(on);
			self.addMessage(new LiteralText("§e[PikaCheck] Alertes pour tous les bans (! orange inclus) : "
				+ (on ? "§aactivées" : "§cdésactivées §7(seulement les ! rouges)")));
			return;
		}

		if (parts.length == 3 && (parts[1].equalsIgnoreCase("auto") || parts[1].equalsIgnoreCase("alert"))
			&& (parts[2].equalsIgnoreCase("on") || parts[2].equalsIgnoreCase("off"))) {
			boolean on = parts[2].equalsIgnoreCase("on");
			String state = on ? "§aactivées" : "§cdésactivées";
			if (parts[1].equalsIgnoreCase("auto")) {
				BanChecker.setAutoEnabled(on);
				self.addMessage(new LiteralText("§e[PikaCheck] Icônes automatiques du tab : " + state));
			} else {
				BanChecker.setAlertEnabled(on);
				self.addMessage(new LiteralText("§e[PikaCheck] Alertes de cheateurs dans le chat : " + state));
			}
			return;
		}

		if (parts.length != 2 || !BanChecker.isValidName(parts[1])) {
			self.addMessage(new LiteralText("§e[PikaCheck] Utilisation : /pcheck <pseudo> | auto on|off | alert on|off | alert all on|off | status"));
			return;
		}

		self.addMessage(new LiteralText("§7[PikaCheck] Recherche de " + parts[1] + "..."));
		BanChecker.check(parts[1]);
	}

	/**
	 * À chaque tick :
	 *  - signale à BanChecker qu'on est connecté à Pika (les recherches auto ne tournent que dans ce cas) ;
	 *  - deux fois par seconde, passe en revue les joueurs proches de toi dans le monde : c'est ce qui
	 *    déclenche la recherche et l'alerte quand tu croises un cheateur, sans ouvrir le tab ;
	 *  - affiche les résultats arrivés depuis le thread de recherche (l'ajout au chat reste sur le thread du jeu).
	 */
	@Inject(method = "tick", at = @At("HEAD"))
	private void pikacheck$onTick(CallbackInfo ci) {
		LocalClientPlayerEntity self = (LocalClientPlayerEntity) (Object) this;

		Minecraft mc = Minecraft.getInstance();
		if (mc.getCurrentServerEntry() != null && BanChecker.isPikaAddress(mc.getCurrentServerEntry().ip)) {
			BanChecker.markOnPika();

			if (++pikacheck$ticks >= SCAN_EVERY_TICKS && self.world != null) {
				pikacheck$ticks = 0;
				java.util.List<PlayerEntity> nearby = self.world.players;
				for (int i = 0; i < nearby.size(); i++) {
					PlayerEntity other = nearby.get(i);
					if (other != null && other != (Object) self) {
						BanChecker.seenNearby(other.getGameProfile().getName());
					}
				}
			}
		}

		String line;
		while ((line = BanChecker.pollMessage()) != null) {
			self.addMessage(new LiteralText(line));
		}
	}
}

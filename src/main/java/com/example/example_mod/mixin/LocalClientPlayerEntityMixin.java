package com.example.example_mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.example_mod.BanChecker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.text.LiteralText;

@Mixin(LocalClientPlayerEntity.class)
public class LocalClientPlayerEntityMixin {

	private static final String COMMAND = "/pcheck";

	/**
	 * Intercepte les commandes "/pcheck ..." avant qu'elles partent au serveur :
	 *   /pcheck <pseudo>      détail des sanctions d'un joueur
	 *   /pcheck auto on|off   active / coupe les icônes automatiques du tab
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

		if (parts.length == 3 && parts[1].equalsIgnoreCase("auto")
			&& (parts[2].equalsIgnoreCase("on") || parts[2].equalsIgnoreCase("off"))) {
			boolean on = parts[2].equalsIgnoreCase("on");
			BanChecker.setAutoEnabled(on);
			self.addMessage(new LiteralText("§e[PikaCheck] Icônes automatiques du tab : " + (on ? "§aactivées" : "§cdésactivées")));
			return;
		}

		if (parts.length != 2 || !BanChecker.isValidName(parts[1])) {
			self.addMessage(new LiteralText("§e[PikaCheck] Utilisation : /pcheck <pseudo> | /pcheck auto on|off | /pcheck status"));
			return;
		}

		self.addMessage(new LiteralText("§7[PikaCheck] Recherche de " + parts[1] + "..."));
		BanChecker.check(parts[1]);
	}

	/**
	 * À chaque tick :
	 *  - signale à BanChecker qu'on est connecté à Pika (les recherches auto ne tournent que dans ce cas) ;
	 *  - affiche les résultats arrivés depuis le thread de recherche (l'ajout au chat reste sur le thread du jeu).
	 */
	@Inject(method = "tick", at = @At("HEAD"))
	private void pikacheck$onTick(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getCurrentServerEntry() != null && BanChecker.isPikaAddress(mc.getCurrentServerEntry().ip)) {
			BanChecker.markOnPika();
		}

		LocalClientPlayerEntity self = (LocalClientPlayerEntity) (Object) this;
		String line;
		while ((line = BanChecker.pollMessage()) != null) {
			self.addMessage(new LiteralText(line));
		}
	}
}

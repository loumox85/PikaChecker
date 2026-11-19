package fr.loumox85.pikacheck.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fr.loumox85.pikacheck.BanChecker;

import net.minecraft.client.gui.overlay.PlayerTabOverlay;
import net.minecraft.client.network.PlayerInfo;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

	/**
	 * Ajoute une icône "!" devant le pseudo dans le tab quand le joueur a des bans.
	 * BanChecker.tabPrefix() ne lit que le cache : aucun appel réseau ici.
	 * Le préfixe fait aussi partie de la largeur de la colonne, donc l'affichage reste aligné.
	 */
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void pikacheck$addIcon(PlayerInfo player, CallbackInfoReturnable<String> cir) {
		String name = player.getProfile().getName();
		String prefix = BanChecker.tabPrefix(name);
		if (!prefix.isEmpty()) {
			cir.setReturnValue(prefix + cir.getReturnValue());
		}
	}
}

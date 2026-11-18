package com.example.example_mod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Récupère et parse l'historique de sanctions d'un joueur depuis la page
 * publique https://pika-network.net/bans/search/<pseudo>/ .
 *
 * Il n'y a pas d'API JSON : on lit le HTML. Les classes CSS utilisées
 * (row, _type, _staff, _reason, _date, _expires) viennent du wrapper
 * "pikanetwork.js". Si Pika change sa page, seul ce fichier est à adapter.
 *
 * Ce fichier ne dépend d'aucune classe Minecraft.
 */
public final class PikaPunishments {

	public static final class Punishment {
		public final String type;
		public final String staff;
		public final String reason;
		public final String date;
		public final String expires;

		public Punishment(String type, String staff, String reason, String date, String expires) {
			this.type = type;
			this.staff = staff;
			this.reason = reason;
			this.date = date;
			this.expires = expires;
		}
	}

	/** Réponse HTTP autre que 200 (ex. 429 = trop de requêtes). */
	public static final class HttpStatusException extends IOException {
		private static final long serialVersionUID = 1L;
		public final int code;
		public final long retryAfterMs; // 0 si le serveur n'a rien précisé

		public HttpStatusException(int code, long retryAfterMs) {
			super("HTTP " + code);
			this.code = code;
			this.retryAfterMs = retryAfterMs;
		}
	}

	private static final String BASE_URL = "https://pika-network.net/bans/search/";
	private static final int MAX_BYTES = 2 * 1024 * 1024;

	private static final Pattern ROW_SPLIT = Pattern.compile("class=\"[^\"]*\\brow\\b[^\"]*\"");
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	// codes couleur Minecraft éventuellement présents dans les raisons
	private static final Pattern MC_CODES = Pattern.compile("(?i)[§&][0-9a-fk-or]");

	private PikaPunishments() {
	}

	/** Télécharge puis parse. Appelle ça depuis un thread secondaire, jamais depuis le thread du jeu. */
	public static List<Punishment> fetch(String playerName) throws IOException {
		String url = BASE_URL + URLEncoder.encode(playerName, "UTF-8") + "/";
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(8000);
		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (PikaCheck mod)");
		conn.setRequestProperty("Accept", "text/html");

		int code = conn.getResponseCode();
		if (code != 200) {
			long retryMs = 0;
			String header = conn.getHeaderField("Retry-After");
			if (header != null) {
				try {
					retryMs = Long.parseLong(header.trim()) * 1000L;
				} catch (NumberFormatException ignored) {
					// date HTTP ou valeur illisible : on garde 0
				}
			}
			conn.disconnect();
			throw new HttpStatusException(code, retryMs);
		}

		try (InputStream in = conn.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
				if (out.size() > MAX_BYTES) {
					throw new IOException("page trop grosse");
				}
			}
			return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
		}
	}

	/** Parse le HTML de la page de recherche. Visible pour pouvoir être testé hors du jeu. */
	public static List<Punishment> parse(String html) {
		List<Punishment> result = new ArrayList<>();

		// le premier morceau est ce qui précède la première ligne : on l'ignore
		String[] chunks = ROW_SPLIT.split(html);
		for (int i = 1; i < chunks.length; i++) {
			String row = chunks[i];

			String type = cell(row, "_type");
			if (type.isEmpty()) {
				continue; // ligne d'en-tête ou autre bloc qui n'est pas une sanction
			}

			result.add(new Punishment(
				type,
				orNA(cell(row, "_staff")),
				orNA(clean(cell(row, "_reason"))),
				cell(row, "_date"),
				cell(row, "_expires")));
		}
		return result;
	}

	/** Texte d'un élément portant la classe donnée (ex. "_type"). Vide si absent. */
	private static String cell(String rowHtml, String cssClass) {
		Pattern p = Pattern.compile(
			"<(\\w+)[^>]*class=\"[^\"]*\\b" + Pattern.quote(cssClass) + "\\b[^\"]*\"[^>]*>(.*?)</\\1>",
			Pattern.DOTALL);
		Matcher m = p.matcher(rowHtml);
		if (!m.find()) {
			return "";
		}
		return text(m.group(2));
	}

	private static String text(String htmlFragment) {
		String s = TAGS.matcher(htmlFragment).replaceAll(" ");
		s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
		return SPACES.matcher(s).replaceAll(" ").trim();
	}

	private static String clean(String reason) {
		return MC_CODES.matcher(reason).replaceAll("").trim();
	}

	private static String orNA(String s) {
		return s.isEmpty() ? "N/A" : s;
	}
}

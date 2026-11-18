package com.example.example_mod;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Cerveau du mod : file d'attente de recherches, limite de débit, cache et
 * coupe-circuit. Ne dépend d'aucune classe Minecraft.
 *
 * Règles pour ne pas se faire limiter par PikaNetwork :
 *  - UN SEUL thread fait les requêtes, jamais plus d'une toutes les MIN_INTERVAL_MS ;
 *  - un joueur déjà connu n'est pas redemandé avant 24 h (cache aussi sur disque) ;
 *  - on ne cherche que les joueurs réellement affichés dans le tab, et seulement sur Pika ;
 *  - plafond de recherches automatiques par session ;
 *  - au premier signe de blocage (429, 403, erreurs répétées) on se met en pause.
 */
public final class BanChecker {

	// ---------- réglages prudents (modifiables ici) ----------
	private static final long MIN_INTERVAL_MS_DEFAULT = 3000;          // 1 requête / 3 s max
	private static final long SUMMARY_TTL_MS = 24L * 3600 * 1000;      // résumé tab valable 24 h
	private static final long LIST_TTL_MS = 30L * 60 * 1000;           // détail /pcheck valable 30 min
	private static final long RETRY_AFTER_FAIL_MS = 10L * 60 * 1000;   // un échec n'est pas retenté avant 10 min
	private static final int MAX_AUTO_PER_SESSION = 120;               // plafond de recherches auto
	private static final int MAX_QUEUE = 60;                           // file auto maximale
	private static final int FAILS_BEFORE_PAUSE = 3;                   // erreurs d'affilée avant pause
	private static final long PAUSE_ERRORS_MS = 10L * 60 * 1000;
	private static final long PAUSE_429_MS = 15L * 60 * 1000;
	private static final long PAUSE_403_MS = 60L * 60 * 1000;
	private static final int MAX_CACHE_LINES = 3000;
	private static final long MAX_STORED_PAUSE_MS = 2L * 3600 * 1000;  // une pause lue sur disque ne dépasse jamais 2 h

	/** Mots (dans la raison d'un ban) qui font penser à un cheat. Adapte-les à ce que tu vois sur Pika. */
	private static final String[] CHEAT_WORDS = {
		"cheat", "hack", "killaura", "kill aura", "reach", "fly", "speed", "velocity", "aimbot",
		"autoclick", "auto click", "xray", "x-ray", "scaffold", "nuker", "antikb", "unfair",
		"forbidden", "[vl", "combat", "movement", "macro"
	};

	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	interface Fetcher {
		List<PikaPunishments.Punishment> fetch(String name) throws IOException;
	}

	/** Résumé d'un joueur, pour l'icône du tab. */
	static final class Status {
		final long time;
		final int bans;
		final int mutes;
		final int others;
		final boolean cheat;

		Status(long time, int bans, int mutes, int others, boolean cheat) {
			this.time = time;
			this.bans = bans;
			this.mutes = mutes;
			this.others = others;
			this.cheat = cheat;
		}

		String prefix() {
			if (cheat) {
				return "§c§l!§r ";
			}
			if (bans > 0) {
				return "§6§l!§r ";
			}
			return "";
		}
	}

	private static final class Entry {
		final long time;
		final List<PikaPunishments.Punishment> list;

		Entry(long time, List<PikaPunishments.Punishment> list) {
			this.time = time;
			this.list = list;
		}
	}

	private static final class Job {
		final String name;
		final boolean manual;

		Job(String name, boolean manual) {
			this.name = name;
			this.manual = manual;
		}
	}

	private static final Map<String, Status> SUMMARIES = new ConcurrentHashMap<>();
	private static final Map<String, Entry> LISTS = new ConcurrentHashMap<>();
	private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> QUEUED = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();
	private static final LinkedBlockingDeque<Job> QUEUE = new LinkedBlockingDeque<>();

	private static final AtomicInteger AUTO_COUNT = new AtomicInteger();
	private static final AtomicInteger REQUESTS = new AtomicInteger();
	private static final AtomicInteger FAILS_IN_A_ROW = new AtomicInteger();

	private static volatile boolean autoEnabled = true;
	private static volatile long onPikaUntil = 0; // on est "sur Pika" tant que le mixin renouvelle cette échéance
	private static volatile long pausedUntil = 0;
	private static volatile long minIntervalMs = MIN_INTERVAL_MS_DEFAULT;
	private static volatile Fetcher fetcher = PikaPunishments::fetch;
	private static volatile Path cachePath = Paths.get("config", "pikacheck-cache.tsv");
	private static volatile Path settingsPath = Paths.get("config", "pikacheck.properties");

	static {
		loadSettings();
		loadCache();
		Thread t = new Thread(BanChecker::workerLoop, "PikaCheck-lookup");
		t.setDaemon(true);
		t.start();
	}

	private BanChecker() {
	}

	// ======================= API utilisée par le mixin =======================

	/** Messages prêts à afficher (à lire depuis le thread du jeu). null s'il n'y en a plus. */
	public static String pollMessage() {
		return PENDING.poll();
	}

	public static boolean isValidName(String name) {
		return VALID_NAME.matcher(name).matches();
	}

	/**
	 * À appeler à chaque tick quand on est connecté à Pika. Les recherches auto ne tournent
	 * que si cet appel a été renouvelé dans les 5 dernières secondes : si tu quittes Pika, tout
	 * s'arrête tout seul (même la file d'attente).
	 */
	public static void markOnPika() {
		onPikaUntil = System.currentTimeMillis() + 5000;
	}

	public static boolean isOnPika() {
		return System.currentTimeMillis() < onPikaUntil;
	}

	/** Vrai si l'adresse du serveur ressemble à Pika (play.pika-network.net, pika.host, ...). */
	public static boolean isPikaAddress(String ip) {
		return ip != null && ip.toLowerCase(Locale.ROOT).contains("pika");
	}

	public static boolean isAutoEnabled() {
		return autoEnabled;
	}

	public static void setAutoEnabled(boolean value) {
		autoEnabled = value;
		saveSettings(); // le réglage survit aux redémarrages
	}

	/**
	 * Préfixe à coller devant un pseudo dans le tab ("" si rien à signaler).
	 * Appelée à chaque image : elle ne fait que lire le cache. Si le joueur est inconnu,
	 * elle le met en file d'attente (jamais d'appel réseau ici).
	 */
	public static String tabPrefix(String name) {
		if (!autoEnabled || !isOnPika() || name == null) {
			return "";
		}
		String key = name.toLowerCase(Locale.ROOT);
		Status s = SUMMARIES.get(key);
		long now = System.currentTimeMillis();
		if (s == null || now - s.time >= SUMMARY_TTL_MS) {
			enqueueAuto(name, key, now);
		}
		return s == null ? "" : s.prefix();
	}

	/** /pcheck <pseudo> : recherche demandée à la main, prioritaire. */
	public static void check(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		Entry e = LISTS.get(key);
		if (e != null && System.currentTimeMillis() - e.time < LIST_TTL_MS) {
			report(name, e.list);
			return;
		}
		if (System.currentTimeMillis() < pausedUntil) {
			PENDING.add(pauseMessage());
			return;
		}
		QUEUE.addFirst(new Job(name, true));
	}

	/** Ligne de diagnostic pour /pcheck status. */
	public static String statusLine() {
		long left = pausedUntil - System.currentTimeMillis();
		return "§7[PikaCheck] auto " + (autoEnabled ? "§aON" : "§cOFF") + "§7 | sur Pika : " + isOnPika()
			+ " | requêtes cette session : " + REQUESTS.get() + " | auto : " + AUTO_COUNT.get() + "/" + MAX_AUTO_PER_SESSION
			+ " | en file : " + QUEUE.size() + " | joueurs connus : " + SUMMARIES.size()
			+ (left > 0 ? " | §cPAUSE " + (left / 60000 + 1) + " min" : "");
	}

	// ============================ file d'attente ============================

	private static void enqueueAuto(String name, String key, long now) {
		if (now < pausedUntil || !isValidName(name)) {
			return;
		}
		if (AUTO_COUNT.get() >= MAX_AUTO_PER_SESSION || QUEUE.size() >= MAX_QUEUE) {
			return;
		}
		Long failedAt = FAILED.get(key);
		if (failedAt != null && now - failedAt < RETRY_AFTER_FAIL_MS) {
			return;
		}
		if (QUEUED.putIfAbsent(key, Boolean.TRUE) != null) {
			return; // déjà en file ou en cours
		}
		AUTO_COUNT.incrementAndGet();
		QUEUE.addLast(new Job(name, false));
	}

	private static void workerLoop() {
		long last = 0;
		while (true) {
			Job job;
			try {
				job = QUEUE.take();
			} catch (InterruptedException e) {
				return;
			}
			String key = job.name.toLowerCase(Locale.ROOT);
			try {
				long now = System.currentTimeMillis();

				if (!job.manual && (!autoEnabled || !isOnPika())) {
					continue; // réglage changé depuis la mise en file
				}
				if (now < pausedUntil) {
					if (job.manual) {
						PENDING.add(pauseMessage());
					}
					continue;
				}
				Entry cached = LISTS.get(key);
				if (cached != null && now - cached.time < LIST_TTL_MS) {
					if (job.manual) {
						report(job.name, cached.list);
					}
					continue; // déjà récupéré récemment : pas de requête
				}

				long wait = last + minIntervalMs - now;
				if (wait > 0) {
					Thread.sleep(wait);
				}
				last = System.currentTimeMillis();

				REQUESTS.incrementAndGet();
				List<PikaPunishments.Punishment> list = fetcher.fetch(job.name);
				onSuccess(job, key, list);
			} catch (PikaPunishments.HttpStatusException e) {
				onHttpError(job, key, e);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				onError(job, key, e.getMessage());
			} finally {
				if (!job.manual) {
					QUEUED.remove(key);
				}
			}
		}
	}

	private static void onSuccess(Job job, String key, List<PikaPunishments.Punishment> list) {
		long now = System.currentTimeMillis();
		FAILS_IN_A_ROW.set(0);
		FAILED.remove(key);
		LISTS.put(key, new Entry(now, list));
		SUMMARIES.put(key, summarize(now, list));
		saveCache();
		if (job.manual) {
			report(job.name, list);
		}
	}

	private static void onHttpError(Job job, String key, PikaPunishments.HttpStatusException e) {
		FAILED.put(key, System.currentTimeMillis());
		if (e.code == 429) {
			pause(Math.max(e.retryAfterMs, PAUSE_429_MS), "Pika limite les requêtes (429)");
		} else if (e.code == 403) {
			pause(PAUSE_403_MS, "Pika refuse les requêtes (403)");
		} else if (e.code >= 500) {
			countFailure("erreur serveur " + e.code);
		}
		if (job.manual) {
			PENDING.add("§c[PikaCheck] Impossible de lire les sanctions de " + job.name + " (" + e.getMessage() + ").");
		}
	}

	private static void onError(Job job, String key, String message) {
		FAILED.put(key, System.currentTimeMillis());
		countFailure("erreurs réseau répétées");
		if (job.manual) {
			PENDING.add("§c[PikaCheck] Impossible de lire les sanctions de " + job.name + " (" + message + ").");
		}
	}

	private static void countFailure(String why) {
		if (FAILS_IN_A_ROW.incrementAndGet() >= FAILS_BEFORE_PAUSE) {
			pause(PAUSE_ERRORS_MS, why);
		}
	}

	private static void pause(long ms, String why) {
		boolean alreadyPaused = System.currentTimeMillis() < pausedUntil;
		pausedUntil = System.currentTimeMillis() + ms;
		FAILS_IN_A_ROW.set(0);
		saveSettings(); // la pause doit survivre à un redémarrage du jeu
		if (!alreadyPaused) {
			PENDING.add("§c[PikaCheck] " + why + " : recherches en pause pendant " + (ms / 60000) + " min.");
		}
	}

	private static String pauseMessage() {
		long left = pausedUntil - System.currentTimeMillis();
		return "§c[PikaCheck] En pause pour ne pas surcharger Pika (encore " + (left / 60000 + 1) + " min).";
	}

	// ============================ résumé / affichage ============================

	static Status summarize(long now, List<PikaPunishments.Punishment> list) {
		int bans = 0;
		int mutes = 0;
		int others = 0;
		boolean cheat = false;
		for (PikaPunishments.Punishment p : list) {
			String t = p.type.toLowerCase(Locale.ROOT);
			if (t.contains("ban")) {
				bans++;
				String reason = p.reason.toLowerCase(Locale.ROOT);
				for (String w : CHEAT_WORDS) {
					if (reason.contains(w)) {
						cheat = true;
						break;
					}
				}
			} else if (t.contains("mute")) {
				mutes++;
			} else {
				others++;
			}
		}
		return new Status(now, bans, mutes, others, cheat);
	}

	private static void report(String name, List<PikaPunishments.Punishment> list) {
		for (String line : format(name, list)) {
			PENDING.add(line);
		}
	}

	/** Met en forme le détail d'un joueur (codes couleur §). */
	public static List<String> format(String name, List<PikaPunishments.Punishment> list) {
		List<String> out = new ArrayList<>();
		if (list.isEmpty()) {
			out.add("§a[PikaCheck] " + name + " : aucune sanction trouvée.");
			return out;
		}
		Status s = summarize(0, list);
		out.add("§e[PikaCheck] " + name + " : §c" + s.bans + " ban(s)§e, " + s.mutes + " mute(s), " + s.others + " autre(s)."
			+ (s.cheat ? " §c§l! raison de type cheat" : ""));

		int shown = Math.min(list.size(), 5);
		for (int i = 0; i < shown; i++) {
			PikaPunishments.Punishment p = list.get(i);
			out.add("§7- §f" + p.type + " §7| " + p.date + " | " + p.reason
				+ (p.expires.isEmpty() ? "" : " §7(expire : " + p.expires + ")"));
		}
		if (list.size() > shown) {
			out.add("§7... et " + (list.size() - shown) + " autre(s) sur pika-network.net/bans");
		}
		return out;
	}

	// ============================ cache sur disque ============================

	private static synchronized void loadCache() {
		Path p = cachePath;
		if (!Files.isRegularFile(p)) {
			return;
		}
		try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			String line;
			long now = System.currentTimeMillis();
			while ((line = r.readLine()) != null) {
				String[] f = line.split("\t");
				if (f.length != 6 || !isValidName(f[0])) {
					continue;
				}
				try {
					long time = Long.parseLong(f[1]);
					if (now - time < SUMMARY_TTL_MS) {
						SUMMARIES.put(f[0], new Status(time, Integer.parseInt(f[2]), Integer.parseInt(f[3]),
							Integer.parseInt(f[4]), "1".equals(f[5])));
					}
				} catch (NumberFormatException ignored) {
					// ligne abîmée : ignorée
				}
			}
		} catch (IOException ignored) {
			// pas de cache : on repart de zéro
		}
	}

	private static synchronized void saveCache() {
		try {
			List<Map.Entry<String, Status>> entries = new ArrayList<>(SUMMARIES.entrySet());
			entries.sort((a, b) -> Long.compare(b.getValue().time, a.getValue().time));
			StringBuilder sb = new StringBuilder();
			int n = 0;
			for (Map.Entry<String, Status> e : entries) {
				if (n++ >= MAX_CACHE_LINES) {
					break;
				}
				Status s = e.getValue();
				sb.append(e.getKey()).append('\t').append(s.time).append('\t').append(s.bans).append('\t')
					.append(s.mutes).append('\t').append(s.others).append('\t').append(s.cheat ? 1 : 0).append('\n');
			}
			Path p = cachePath;
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
			Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// le cache disque est un confort, pas une nécessité
		}
	}

	// ============================ réglages sur disque ============================

	private static synchronized void loadSettings() {
		Path p = settingsPath;
		if (!Files.isRegularFile(p)) {
			return; // première fois : valeurs par défaut (auto activé)
		}
		try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			Properties props = new Properties();
			props.load(r);
			autoEnabled = !"false".equalsIgnoreCase(props.getProperty("auto", "true").trim());

			// pause restante d'un blocage précédent (429 / 403 / erreurs) : elle survit au redémarrage
			try {
				long until = Long.parseLong(props.getProperty("pausedUntil", "0").trim());
				long now = System.currentTimeMillis();
				if (until > now) {
					pausedUntil = Math.min(until, now + MAX_STORED_PAUSE_MS);
					PENDING.add("§c[PikaCheck] Pause encore active (" + ((pausedUntil - now) / 60000 + 1)
						+ " min) suite à un blocage précédent de Pika.");
				}
			} catch (NumberFormatException ignored) {
				// valeur abîmée : pas de pause
			}
		} catch (IOException ignored) {
			// fichier illisible : on garde la valeur par défaut
		}
	}

	private static synchronized void saveSettings() {
		try {
			Path p = settingsPath;
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			Properties props = new Properties();
			props.setProperty("auto", String.valueOf(autoEnabled));
			props.setProperty("pausedUntil", String.valueOf(Math.max(pausedUntil, 0)));
			try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				props.store(w, "PikaCheck - /pcheck auto on|off");
			}
		} catch (IOException ignored) {
			// pas grave : le réglage ne sera juste pas retenu au prochain lancement
		}
	}

	// ============================ hooks de test ============================

	static void setFetcherForTests(Fetcher f) {
		fetcher = f;
	}

	static void setMinIntervalForTests(long ms) {
		minIntervalMs = ms;
	}

	static void setCachePathForTests(Path p) {
		cachePath = p;
	}

	static int requestsForTests() {
		return REQUESTS.get();
	}
}

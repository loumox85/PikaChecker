package fr.loumox85.pikacheck;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
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
 * Cerveau du mod : file d'attente de recherches, limite de débit, cache, coupe-circuit
 * et alertes dans le chat. Ne dépend d'aucune classe Minecraft.
 *
 * Règles pour ne pas se faire limiter par PikaNetwork :
 *  - UN SEUL thread fait les requêtes, jamais plus d'une toutes les MIN_INTERVAL_MS ;
 *  - un joueur déjà connu n'est pas redemandé avant 24 h (cache aussi sur disque) ;
 *  - on ne cherche que les joueurs réellement affichés dans le tab, et seulement sur Pika ;
 *  - plafond de recherches automatiques par heure glissante (prévenu dans le chat) ;
 *  - au premier signe de blocage (429, 403, erreurs répétées) on se met en pause
 *    (sauvegardée sur disque) et on te prévient, puis on te prévient quand ça reprend.
 */
public final class BanChecker {

	// ---------- réglages prudents (modifiables ici) ----------
	private static final long MIN_INTERVAL_MS_DEFAULT = 3000;          // 1 requête / 3 s max
	private static final long SUMMARY_TTL_MS = 24L * 3600 * 1000;      // résumé tab valable 24 h
	private static final long LIST_TTL_MS = 30L * 60 * 1000;           // détail /pcheck valable 30 min
	private static final long RETRY_AFTER_FAIL_MS = 10L * 60 * 1000;   // un échec n'est pas retenté avant 10 min
	private static final long HOUR_MS = 3600L * 1000;
	private static final int MAX_AUTO_PER_HOUR_DEFAULT = 200;          // recherches auto max sur 1 h glissante
	private static final int MAX_QUEUE = 60;                           // file auto maximale
	private static final int FAILS_BEFORE_PAUSE = 3;                   // erreurs d'affilée avant pause
	private static final long PAUSE_ERRORS_MS = 10L * 60 * 1000;
	private static final long PAUSE_429_MS = 15L * 60 * 1000;
	private static final long PAUSE_403_MS = 60L * 60 * 1000;
	private static final long ERROR_NOTICE_EVERY_MS = 5L * 60 * 1000;  // un message d'erreur max / 5 min
	private static final long MAX_STORED_PAUSE_MS = 2L * 3600 * 1000;  // une pause lue sur disque ne dépasse jamais 2 h
	private static final int MAX_CACHE_LINES = 3000;

	/** Mots (dans la raison d'un ban) qui font penser à un cheat. Adapte-les à ce que tu vois sur Pika. */
	private static final String[] CHEAT_WORDS = {
		"cheat", "hack", "killaura", "kill aura", "reach", "fly", "speed", "velocity", "aimbot",
		"autoclick", "auto click", "xray", "x-ray", "scaffold", "nuker", "antikb", "unfair",
		"forbidden", "[vl", "combat", "movement", "macro"
	};

	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	/** Réglages modifiables (dans le menu OneConfig via la config OSL). */
	public interface Settings {
		boolean auto();

		boolean alerts();

		/** false : alerte seulement pour les ! rouges (ban de type cheat). true : aussi pour les ! orange (tout autre ban). */
		boolean alertAll();

		/** Un joueur déjà signalé n'est re-signalé que s'il a disparu au moins ce nombre de minutes. */
		int realertMinutes();

		int maxAutoPerHour();

		long minIntervalMs();

		void setAuto(boolean value);

		void setAlerts(boolean value);

		void setAlertAll(boolean value);
	}

	/** Valeurs par défaut utilisées tant qu'aucune config n'est branchée (et dans les tests). */
	private static final class MemorySettings implements Settings {
		volatile boolean auto = true;
		volatile boolean alerts = true;
		volatile boolean alertAll = false;
		volatile int realertMinutes = 3;
		volatile int maxAutoPerHour = MAX_AUTO_PER_HOUR_DEFAULT;
		volatile long minIntervalMs = MIN_INTERVAL_MS_DEFAULT;

		public boolean auto() {
			return auto;
		}

		public boolean alerts() {
			return alerts;
		}

		public boolean alertAll() {
			return alertAll;
		}

		public int realertMinutes() {
			return realertMinutes;
		}

		public int maxAutoPerHour() {
			return maxAutoPerHour;
		}

		public long minIntervalMs() {
			return minIntervalMs;
		}

		public void setAuto(boolean value) {
			auto = value;
		}

		public void setAlerts(boolean value) {
			alerts = value;
		}

		public void setAlertAll(boolean value) {
			alertAll = value;
		}
	}

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
	private static final Map<String, Long> LAST_SEEN = new ConcurrentHashMap<>();  // dernière fois où le joueur a été vu
	private static final Map<String, Long> LAST_ALERT = new ConcurrentHashMap<>(); // dernière alerte envoyée pour ce joueur
	private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();
	private static final LinkedBlockingDeque<Job> QUEUE = new LinkedBlockingDeque<>();
	private static final ArrayDeque<Long> AUTO_TIMES = new ArrayDeque<>(); // horodatages des recherches auto (1 h glissante)

	private static final AtomicInteger REQUESTS = new AtomicInteger();
	private static final AtomicInteger FAILS_IN_A_ROW = new AtomicInteger();

	private static final MemorySettings MEMORY = new MemorySettings();
	private static volatile Settings settings = MEMORY;
	private static volatile long onPikaUntil = 0; // on est "sur Pika" tant que le mixin renouvelle cette échéance
	private static volatile long pausedUntil = 0;
	private static volatile boolean worldScanActive = false; // le scan des joueurs proches tourne : le tab n'envoie plus d'alertes
	private static volatile boolean pauseResumeNotice = false;
	private static volatile boolean capNoticeShown = false;
	private static volatile long lastErrorNotice = 0;
	private static volatile long clockOffset = 0; // uniquement pour les tests
	private static volatile Fetcher fetcher = PikaPunishments::fetch;
	private static volatile Path cachePath = Paths.get("config", "pikacheck-cache.tsv");
	private static volatile Path statePath = Paths.get("config", "pikacheck-state.properties");

	static {
		loadSettings();
		loadCache();
		Thread t = new Thread(BanChecker::workerLoop, "PikaCheck-lookup");
		t.setDaemon(true);
		t.start();
	}

	private BanChecker() {
	}

	private static long now() {
		return System.currentTimeMillis() + clockOffset;
	}

	// ======================= API utilisée par les mixins =======================

	/** Messages prêts à afficher (à lire depuis le thread du jeu, à chaque tick). null s'il n'y en a plus. */
	public static String pollMessage() {
		checkNotices(now());
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
		onPikaUntil = now() + 5000;
	}

	public static boolean isOnPika() {
		return now() < onPikaUntil;
	}

	/** Vrai si l'adresse du serveur ressemble à Pika (play.pika-network.net, pika.host, ...). */
	public static boolean isPikaAddress(String ip) {
		return ip != null && ip.toLowerCase(Locale.ROOT).contains("pika");
	}

	/** Branche les vrais réglages (config OSL, visible dans le menu OneConfig). */
	public static void setSettings(Settings value) {
		settings = value == null ? MEMORY : value;
	}

	public static boolean isAutoEnabled() {
		return settings.auto();
	}

	public static void setAutoEnabled(boolean value) {
		settings.setAuto(value);
	}

	public static boolean isAlertEnabled() {
		return settings.alerts();
	}

	public static void setAlertEnabled(boolean value) {
		settings.setAlerts(value);
	}

	public static boolean isAlertAllEnabled() {
		return settings.alertAll();
	}

	public static void setAlertAllEnabled(boolean value) {
		settings.setAlertAll(value);
	}

	/**
	 * Préfixe à coller devant un pseudo dans le tab ("" si rien à signaler).
	 * Appelée à chaque image : elle ne fait que lire le cache. Si le joueur est inconnu,
	 * elle le met en file d'attente (jamais d'appel réseau ici).
	 */
	public static String tabPrefix(String name) {
		if (!settings.auto() || !isOnPika() || name == null) {
			return "";
		}
		String key = name.toLowerCase(Locale.ROOT);
		long n = now();
		Status s = SUMMARIES.get(key);
		if (s == null || n - s.time >= SUMMARY_TTL_MS) {
			enqueueAuto(name, key, n);
		}
		if (s == null) {
			return "";
		}
		observe(name, key, n, s, true);
		return s.prefix();
	}

	/**
	 * À appeler (environ deux fois par seconde) pour chaque joueur proche de toi dans le monde.
	 * Lance la recherche s'il est inconnu et t'alerte dans le chat dès que c'est un cheateur,
	 * sans que tu aies besoin d'ouvrir le tab. Re-alerte si tu le recroises plus tard.
	 */
	public static void seenNearby(String name) {
		worldScanActive = true;
		if (!settings.auto() || !isOnPika() || name == null) {
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		long n = now();
		Status s = SUMMARIES.get(key);
		if (s == null || n - s.time >= SUMMARY_TTL_MS) {
			enqueueAuto(name, key, n);
		}
		observe(name, key, n, s, false);
	}

	/**
	 * Décide si une alerte doit partir. Règles :
	 *  - ! rouge (ban de type cheat) : toujours ; ! orange (autre ban) : seulement si "alertAll" ;
	 *  - une alerte à la première rencontre, puis une nouvelle seulement si le joueur a disparu
	 *    au moins realertMinutes minutes (et que la dernière alerte date d'autant) ;
	 *  - une fois le scan des joueurs proches actif, ouvrir le tab ne déclenche plus d'alerte.
	 */
	private static void observe(String name, String key, long n, Status s, boolean fromTab) {
		if (fromTab && worldScanActive) {
			return;
		}
		Long lastSeen = LAST_SEEN.put(key, n);
		if (s == null || !settings.alerts()) {
			return;
		}
		boolean worth = s.cheat || (settings.alertAll() && s.bans > 0);
		if (!worth) {
			return;
		}
		long gap = Math.max(1, settings.realertMinutes()) * 60_000L;
		Long lastAlert = LAST_ALERT.get(key);
		boolean absentLongEnough = lastSeen == null || n - lastSeen >= gap;
		if (lastAlert == null || (absentLongEnough && n - lastAlert >= gap)) {
			LAST_ALERT.put(key, n);
			PENDING.add(alertMessage(name, key, s));
		}
	}

	/** /pcheck <pseudo> : recherche demandée à la main, prioritaire. */
	public static void check(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		Entry e = LISTS.get(key);
		if (e != null && now() - e.time < LIST_TTL_MS) {
			report(name, e.list);
			return;
		}
		if (now() < pausedUntil) {
			PENDING.add(pauseMessage());
			return;
		}
		QUEUE.addFirst(new Job(name, true));
	}

	/** Ligne de diagnostic pour /pcheck status. */
	public static String statusLine() {
		long n = now();
		long left = pausedUntil - n;
		int used;
		synchronized (AUTO_TIMES) {
			purge(n);
			used = AUTO_TIMES.size();
		}
		return "§7[PikaCheck] auto " + (settings.auto() ? "§aON" : "§cOFF") + "§7 | alertes " + (settings.alerts() ? (settings.alertAll() ? "§aTOUS BANS" : "§aCHEAT") : "§cOFF")
			+ "§7 | sur Pika : " + isOnPika() + " | requêtes cette session : " + REQUESTS.get()
			+ " | auto sur 1 h : " + used + "/" + settings.maxAutoPerHour()
			+ " | en file : " + QUEUE.size() + " | joueurs connus : " + SUMMARIES.size()
			+ (left > 0 ? " | §cPAUSE " + (left / 60000 + 1) + " min" : "");
	}

	// ============================ file d'attente ============================

	private static void purge(long n) { // à appeler sous synchronized (AUTO_TIMES)
		while (!AUTO_TIMES.isEmpty() && n - AUTO_TIMES.peekFirst() >= HOUR_MS) {
			AUTO_TIMES.pollFirst();
		}
	}

	private static void enqueueAuto(String name, String key, long n) {
		if (n < pausedUntil || !isValidName(name) || QUEUE.size() >= MAX_QUEUE) {
			return;
		}
		Long failedAt = FAILED.get(key);
		if (failedAt != null && n - failedAt < RETRY_AFTER_FAIL_MS) {
			return;
		}
		if (QUEUED.containsKey(key)) {
			return; // déjà en file ou en cours
		}
		synchronized (AUTO_TIMES) {
			purge(n);
			if (AUTO_TIMES.size() >= settings.maxAutoPerHour()) {
				if (!capNoticeShown) {
					capNoticeShown = true;
					long wait = HOUR_MS - (n - AUTO_TIMES.peekFirst());
					PENDING.add("§c[PikaCheck] Limite de " + settings.maxAutoPerHour() + " recherches auto par heure atteinte : les nouveaux "
						+ "joueurs ne sont plus vérifiés (reprise dans ~" + (wait / 60000 + 1) + " min). /pcheck <pseudo> reste possible.");
				}
				return;
			}
			if (QUEUED.putIfAbsent(key, Boolean.TRUE) != null) {
				return;
			}
			AUTO_TIMES.addLast(n);
		}
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
				long n = now();

				if (!job.manual && (!settings.auto() || !isOnPika())) {
					continue; // réglage changé ou Pika quitté depuis la mise en file
				}
				if (n < pausedUntil) {
					if (job.manual) {
						PENDING.add(pauseMessage());
					}
					continue;
				}
				Entry cached = LISTS.get(key);
				if (cached != null && n - cached.time < LIST_TTL_MS) {
					if (job.manual) {
						report(job.name, cached.list);
					}
					continue; // déjà récupéré récemment : pas de requête
				}

				long wait = last + settings.minIntervalMs() - n;
				if (wait > 0) {
					Thread.sleep(wait);
				}
				last = now();

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
		long n = now();
		FAILS_IN_A_ROW.set(0);
		FAILED.remove(key);
		LISTS.put(key, new Entry(n, list));
		SUMMARIES.put(key, summarize(n, list));
		saveCache();
		if (job.manual) {
			report(job.name, list);
		}
	}

	private static void onHttpError(Job job, String key, PikaPunishments.HttpStatusException e) {
		FAILED.put(key, now());
		if (e.code == 429) {
			pause(Math.max(e.retryAfterMs, PAUSE_429_MS), "Pika limite les requêtes (429)");
		} else if (e.code == 403) {
			pause(PAUSE_403_MS, "Pika refuse les requêtes (403)");
		} else {
			if (e.code >= 500) {
				countFailure("erreur serveur " + e.code);
			}
			noticeError(job, e.getMessage());
		}
		if (job.manual) {
			PENDING.add("§c[PikaCheck] Impossible de lire les sanctions de " + job.name + " (" + e.getMessage() + ").");
		}
	}

	private static void onError(Job job, String key, String message) {
		FAILED.put(key, now());
		countFailure("erreurs réseau répétées");
		noticeError(job, message);
		if (job.manual) {
			PENDING.add("§c[PikaCheck] Impossible de lire les sanctions de " + job.name + " (" + message + ").");
		}
	}

	/** Prévient dans le chat d'une erreur de recherche automatique (au plus un message toutes les 5 min). */
	private static void noticeError(Job job, String what) {
		if (job.manual) {
			return; // les recherches manuelles ont déjà leur propre message d'erreur
		}
		long n = now();
		if (n - lastErrorNotice >= ERROR_NOTICE_EVERY_MS) {
			lastErrorNotice = n;
			PENDING.add("§e[PikaCheck] Erreur lors d'une recherche automatique (" + what + "). Nouvel essai plus tard.");
		}
	}

	private static void countFailure(String why) {
		if (FAILS_IN_A_ROW.incrementAndGet() >= FAILS_BEFORE_PAUSE) {
			pause(PAUSE_ERRORS_MS, why);
		}
	}

	private static void pause(long ms, String why) {
		boolean alreadyPaused = now() < pausedUntil;
		pausedUntil = now() + ms;
		pauseResumeNotice = true; // on te prévient aussi quand la pause se termine
		FAILS_IN_A_ROW.set(0);
		saveSettings(); // la pause doit survivre à un redémarrage du jeu
		if (!alreadyPaused) {
			PENDING.add("§c[PikaCheck] " + why + " : recherches en pause pendant " + (ms / 60000) + " min.");
		}
	}

	private static String pauseMessage() {
		long left = pausedUntil - now();
		return "§c[PikaCheck] En pause pour ne pas surcharger Pika (encore " + (left / 60000 + 1) + " min).";
	}

	/** Messages "c'est reparti" : fin de pause, fin de la limite horaire. Appelée à chaque tick. */
	private static void checkNotices(long n) {
		if (pauseResumeNotice && n >= pausedUntil) {
			pauseResumeNotice = false;
			PENDING.add("§a[PikaCheck] Pause terminée : les recherches automatiques reprennent.");
		}
		if (capNoticeShown) {
			synchronized (AUTO_TIMES) {
				purge(n);
				if (AUTO_TIMES.size() < settings.maxAutoPerHour()) {
					capNoticeShown = false;
					PENDING.add("§a[PikaCheck] Limite horaire levée : les nouveaux joueurs sont de nouveau vérifiés.");
				}
			}
		}
	}

	// ============================ résumé / affichage ============================

	static boolean isCheatReason(String reason) {
		String r = reason.toLowerCase(Locale.ROOT);
		for (String w : CHEAT_WORDS) {
			if (r.contains(w)) {
				return true;
			}
		}
		return false;
	}

	/** Raison du premier ban de type cheat, ou null. */
	static String cheatReasonOf(List<PikaPunishments.Punishment> list) {
		for (PikaPunishments.Punishment p : list) {
			if (p.type.toLowerCase(Locale.ROOT).contains("ban") && isCheatReason(p.reason)) {
				return p.reason;
			}
		}
		return null;
	}

	static Status summarize(long n, List<PikaPunishments.Punishment> list) {
		int bans = 0;
		int mutes = 0;
		int others = 0;
		boolean cheat = false;
		for (PikaPunishments.Punishment p : list) {
			String t = p.type.toLowerCase(Locale.ROOT);
			if (t.contains("ban")) {
				bans++;
				if (isCheatReason(p.reason)) {
					cheat = true;
				}
			} else if (t.contains("mute")) {
				mutes++;
			} else {
				others++;
			}
		}
		return new Status(n, bans, mutes, others, cheat);
	}

	private static String alertMessage(String name, String key, Status s) {
		Entry e = LISTS.get(key);
		if (s.cheat) {
			String reason = e == null ? null : cheatReasonOf(e.list);
			return "§c§l[!] §c" + name + "§7 : ban de type cheat sur Pika"
				+ (reason == null ? "" : " §7(" + reason + ")") + " §8- /pcheck " + name;
		}
		String reason = e == null ? null : firstBanReason(e.list);
		return "§6§l[!] §6" + name + "§7 : déjà banni sur Pika"
			+ (reason == null ? "" : " §7(" + reason + ")") + " §8- /pcheck " + name;
	}

	/** Raison du premier ban, ou null. */
	static String firstBanReason(List<PikaPunishments.Punishment> list) {
		for (PikaPunishments.Punishment p : list) {
			if (p.type.toLowerCase(Locale.ROOT).contains("ban")) {
				return p.reason;
			}
		}
		return null;
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
			long n = now();
			while ((line = r.readLine()) != null) {
				String[] f = line.split("\t");
				if (f.length != 6 || !isValidName(f[0])) {
					continue;
				}
				try {
					long time = Long.parseLong(f[1]);
					if (n - time < SUMMARY_TTL_MS) {
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

	// ============================ état sur disque (pause) ============================

	private static synchronized void loadSettings() {
		Path p = statePath;
		if (!Files.isRegularFile(p)) {
			return;
		}
		try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			Properties props = new Properties();
			props.load(r);

			// pause restante d'un blocage précédent (429 / 403 / erreurs) : elle survit au redémarrage
			try {
				long until = Long.parseLong(props.getProperty("pausedUntil", "0").trim());
				long n = now();
				if (until > n) {
					pausedUntil = Math.min(until, n + MAX_STORED_PAUSE_MS);
					pauseResumeNotice = true;
					PENDING.add("§c[PikaCheck] Pause encore active (" + ((pausedUntil - n) / 60000 + 1)
						+ " min) suite à un blocage précédent de Pika.");
				}
			} catch (NumberFormatException ignored) {
				// valeur abîmée : pas de pause
			}
		} catch (IOException ignored) {
			// fichier illisible : pas de pause
		}
	}

	private static synchronized void saveSettings() {
		try {
			Path p = statePath;
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			Properties props = new Properties();
			props.setProperty("pausedUntil", String.valueOf(Math.max(pausedUntil, 0)));
			try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				props.store(w, "PikaCheck - etat (pause apres un blocage de Pika). Les reglages sont dans le menu OneConfig.");
			}
		} catch (IOException ignored) {
			// pas grave : la pause ne sera juste pas retenue au prochain lancement
		}
	}

	// ============================ hooks de test ============================

	static void setFetcherForTests(Fetcher f) {
		fetcher = f;
	}

	static void setMinIntervalForTests(long ms) {
		MEMORY.minIntervalMs = ms;
	}

	static void setAlertAllForTests(boolean value) {
		MEMORY.alertAll = value;
	}

	static void setRealertMinutesForTests(int minutes) {
		MEMORY.realertMinutes = minutes;
	}

	static void setMaxAutoPerHourForTests(int max) {
		MEMORY.maxAutoPerHour = max;
	}

	static void advanceClockForTests(long ms) {
		clockOffset += ms;
	}

	static int requestsForTests() {
		return REQUESTS.get();
	}
}

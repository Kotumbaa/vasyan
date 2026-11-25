/**
 * Liner: Перенос треков из плейлиста в «Лайкнутые» (Liked Tracks)
 *
 * Как использовать:
 * 1. Импортируйте плейлист из Spotify в Liner стандартным способом (Создать плейлист -> Импорт).
 * 2. В открытом окне Liner нажмите F12 (или Ctrl+Shift+I) и откройте вкладку Console.
 * 3. Скопируйте и вставьте весь этот файл в консоль, затем нажмите Enter.
 * 4. Следуйте подсказке в консоли:
 *    - await linerLikeHelper.list()                    // показать список плейлистов
 *    - await linerLikeHelper.transfer(1)               // перенести плейлист №1 из списка
 *    - await linerLikeHelper.transfer("Мой плейлист")  // перенести по названию или ID
 *    - await linerLikeHelper.transferCurrent()         // перенести открытый сейчас плейлист
 *    - await linerLikeHelper.syncOrder(1)              // расставить треки в «Лайкнутых» строго в порядке плейлиста №1
 *    - await linerLikeHelper.syncCurrentOrder()        // то же самое для открытого сейчас плейлиста
 *    - linerLikeHelper.stop()                          // остановить перенос / сортировку
 *
 * Опции (второй аргумент transfer / первый аргумент transferCurrent):
 *    {
 *      preserveOrder: true,        // лайкать с конца, чтобы порядок в «Лайкнутых» совпал с плейлистом
 *      delayMs: 180,               // пауза между запросами (мс), чтобы не словить 429 и сохранить порядок
 *      skipAlreadyLiked: true,     // пропускать уже лайкнутые треки
 *      dryRun: false,              // true — только проверить, ничего не лайкать
 *      deletePlaylistAfter: false, // true — удалить исходный плейлист, если все треки перенеслись без ошибок
 *    }
 */
(async () => {
  const API_BASE =
    (typeof globalThis !== "undefined" &&
      (globalThis.LINER_API_BASE ||
        (typeof process !== "undefined" && process.env?.LINER_API_BASE))) ||
    "https://liner-worker.vasyan.workers.dev";
  const bridge = window.linerElectron;

  if (!bridge || typeof bridge.signRequest !== "function" || !bridge.authSession) {
    console.error(
      "[linerLikeHelper] window.linerElectron не найден. Запустите скрипт в DevTools (F12) внутри приложения Liner.",
    );
    return;
  }

  let abortRequested = false;
  let cachedPlaylists = [];
  const appVersion = await bridge.getAppVersion?.().catch(() => "1.0.4-desktop");

  function getPlatform() {
    const ua = (navigator.userAgent || "").toLowerCase();
    if (ua.includes("win")) return "windows";
    if (ua.includes("mac")) return "macos";
    if (ua.includes("linux")) return "linux";
    return "desktop";
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  function formatTrack(track) {
    const artists = Array.isArray(track?.artists)
      ? track.artists.map((a) => (typeof a === "string" ? a : a?.name)).filter(Boolean).join(", ")
      : typeof track?.artists === "string"
        ? track.artists
        : "";
    return artists ? `${artists} — ${track?.title || track?.id}` : `${track?.title || track?.id}`;
  }

  async function apiRequest(path, { method = "GET", body = null, retries = 4 } = {}) {
    const cleanPath = path.split("?")[0].trim();
    const rawBody = body == null ? null : typeof body === "string" ? body : JSON.stringify(body);
    let refreshedOnce = false;

    for (let attempt = 0; attempt <= retries; attempt++) {
      let session = bridge.authSession.get();
      if (!session?.accessToken) {
        throw new Error("Вы не авторизованы в Liner (нет активной сессии).");
      }

      const signed = await bridge.signRequest({
        method: method.toUpperCase(),
        path: cleanPath,
        body: rawBody ?? undefined,
      });

      const headers = {
        Authorization: `Bearer ${session.accessToken}`,
        "x-request-id": crypto.randomUUID(),
        "x-client-version": appVersion || "1.0.4-desktop",
        "x-platform": getPlatform(),
      };

      if (rawBody !== null) headers["Content-Type"] = "application/json";
      if (session.user?.id) headers["x-user-id"] = String(session.user.id);
      if (session.user?.username) headers["x-user-name"] = String(session.user.username);
      if (signed?.signature) {
        headers["x-liner-signature"] = signed.signature;
        headers["x-liner-timestamp"] = String(signed.timestamp);
        headers["x-liner-nonce"] = signed.nonce;
      }

      const res = await fetch(`${API_BASE}${path}`, {
        method: method.toUpperCase(),
        headers,
        body: rawBody ?? undefined,
      });

      const dateHeader = res.headers.get("date");
      if (dateHeader && typeof bridge.syncServerTime === "function") {
        await bridge.syncServerTime(dateHeader).catch(() => {});
      }

      if (res.status === 401 && !refreshedOnce && session.refreshToken) {
        refreshedOnce = true;
        console.warn("[linerLikeHelper] Токен истёк, обновляем сессию...");
        const refreshed = await bridge.authSession.refresh({
          apiBaseUrl: API_BASE,
          refreshToken: session.refreshToken,
        });
        if (!refreshed?.session?.accessToken) {
          throw new Error("Не удалось обновить токен авторизации.");
        }
        continue;
      }

      if (res.status === 429 || (res.status >= 500 && res.status < 600)) {
        if (attempt < retries) {
          const retryAfter = Number(res.headers.get("retry-after"));
          const waitMs =
            Number.isFinite(retryAfter) && retryAfter > 0
              ? retryAfter * 1000
              : Math.min(15000, 1000 * Math.pow(2, attempt));
          console.warn(
            `[linerLikeHelper] HTTP ${res.status} на ${method} ${path}, повтор через ${waitMs} мс...`,
          );
          await sleep(waitMs);
          continue;
        }
      }

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(`HTTP ${res.status} ${res.statusText} (${method} ${path}): ${text}`);
      }

      if (res.status === 204) return null;
      const contentType = res.headers.get("content-type") || "";
      if (contentType.includes("application/json")) {
        return res.json();
      }
      return null;
    }
  }

  async function listPlaylists() {
    const data = await apiRequest("/v1/me/playlists");
    const items = Array.isArray(data?.items) ? data.items : [];
    cachedPlaylists = items;

    if (items.length === 0) {
      console.log("[linerLikeHelper] Плейлистов не найдено.");
      return [];
    }

    console.table(
      items.map((p, idx) => ({
        "#": idx + 1,
        title: p.title,
        tracks: p.trackCount ?? "?",
        id: p.id,
      })),
    );
    return items;
  }

  async function fetchAllLikedOrder() {
    const orderedIds = [];
    const seen = new Set();
    let cursor = undefined;
    let pages = 0;

    do {
      const params = new URLSearchParams({ limit: "100" });
      if (cursor) params.set("cursor", cursor);
      const page = await apiRequest(`/v1/me/library/tracks?${params.toString()}`);
      const items = Array.isArray(page?.items) ? page.items : [];
      for (const entry of items) {
        const id = entry?.track?.id;
        if (id && !seen.has(id)) {
          seen.add(id);
          orderedIds.push(id);
        }
      }
      cursor = page?.nextCursor || undefined;
      pages += 1;
    } while (cursor && pages < 500);

    return orderedIds;
  }

  async function fetchAllLikedIds() {
    return new Set(await fetchAllLikedOrder());
  }

  async function resolvePlaylist(selector) {
    if (cachedPlaylists.length === 0) {
      const data = await apiRequest("/v1/me/playlists");
      cachedPlaylists = Array.isArray(data?.items) ? data.items : [];
    }

    if (typeof selector === "number" && Number.isInteger(selector)) {
      const found = cachedPlaylists[selector - 1];
      if (!found) {
        throw new Error(
          `Плейлист с номером #${selector} не найден. Всего плейлистов: ${cachedPlaylists.length}.`,
        );
      }
      return found;
    }

    const query = String(selector || "").trim();
    if (!query) {
      throw new Error("Укажите номер, название или ID плейлиста.");
    }

    const byId = cachedPlaylists.find((p) => p.id === query);
    if (byId) return byId;

    const lower = query.toLowerCase();
    const exact = cachedPlaylists.find((p) => (p.title || "").toLowerCase() === lower);
    if (exact) return exact;

    const partial = cachedPlaylists.filter((p) =>
      (p.title || "").toLowerCase().includes(lower),
    );
    if (partial.length === 1) return partial[0];
    if (partial.length > 1) {
      console.table(partial.map((p) => ({ title: p.title, id: p.id, tracks: p.trackCount })));
      throw new Error(
        `Найдено несколько плейлистов по запросу "${query}". Укажите точный ID или номер из list().`,
      );
    }

    // Если передали ID, которого нет в кэше — попробуем запросить напрямую
    return { id: query, title: query };
  }

  function getCurrentPlaylistId() {
    const sources = [window.location.hash || "", window.location.search || ""];
    for (const src of sources) {
      const qIndex = src.indexOf("?");
      if (qIndex === -1) continue;
      const params = new URLSearchParams(src.slice(qIndex + 1));
      const id = params.get("id");
      if (id) return id;
    }
    return null;
  }

  async function transfer(selector, options = {}) {
    const {
      preserveOrder = true,
      delayMs = 180,
      skipAlreadyLiked = true,
      dryRun = false,
      deletePlaylistAfter = false,
    } = options;

    abortRequested = false;

    const target = await resolvePlaylist(selector);
    console.log(`[linerLikeHelper] Загружаем плейлист "${target.title}" (${target.id})...`);

    const detail = await apiRequest(`/v1/me/playlists/${encodeURIComponent(target.id)}`);
    const playlistTitle = detail?.playlist?.title || target.title;
    const rawItems = Array.isArray(detail?.items) ? detail.items : [];

    // Убираем локальные треки и дубликаты внутри плейлиста, сохраняя порядок
    const seen = new Set();
    const tracks = [];
    let localSkipped = 0;

    for (const item of rawItems) {
      const track = item?.track;
      if (!track?.id) continue;
      if (String(track.id).startsWith("local:")) {
        localSkipped += 1;
        continue;
      }
      if (seen.has(track.id)) continue;
      seen.add(track.id);
      tracks.push(track);
    }

    console.log(
      `[linerLikeHelper] В плейлисте "${playlistTitle}" найдено уникальных треков: ${tracks.length}` +
        (localSkipped ? ` (пропущено локальных: ${localSkipped})` : ""),
    );

    if (tracks.length === 0) {
      console.log("[linerLikeHelper] Нечего переносить.");
      return { total: 0, liked: 0, skipped: 0, failed: [] };
    }

    let alreadyLiked = new Set();
    if (skipAlreadyLiked) {
      console.log("[linerLikeHelper] Загружаем список уже лайкнутых треков...");
      alreadyLiked = await fetchAllLikedIds();
      console.log(`[linerLikeHelper] Уже лайкнуто треков в аккаунте: ${alreadyLiked.size}`);
    }

    const queue = preserveOrder ? [...tracks].reverse() : [...tracks];
    const toLike = queue.filter((t) => !alreadyLiked.has(t.id));
    const skippedCount = queue.length - toLike.length;

    console.log(
      `[linerLikeHelper] К переносу: ${toLike.length}, уже в лайкнутых (пропуск): ${skippedCount}` +
        (dryRun ? " [DRY RUN — изменения НЕ вносятся]" : ""),
    );

    let likedCount = 0;
    const failed = [];

    for (let i = 0; i < toLike.length; i++) {
      if (abortRequested) {
        console.warn(`[linerLikeHelper] Остановлено пользователем на ${i}/${toLike.length}.`);
        break;
      }

      const track = toLike[i];
      const label = formatTrack(track);

      if (dryRun) {
        console.log(`[${i + 1}/${toLike.length}] [dryRun] ${label}`);
        likedCount += 1;
        continue;
      }

      try {
        await apiRequest(`/v1/me/library/tracks/${encodeURIComponent(track.id)}`, {
          method: "PUT",
          body: { track, trackSnapshot: track },
        });
        likedCount += 1;
        console.log(`[${i + 1}/${toLike.length}] ✓ ${label}`);
      } catch (err) {
        failed.push({ id: track.id, label, error: err?.message || String(err) });
        console.error(`[${i + 1}/${toLike.length}] ✗ ${label}:`, err?.message || err);
      }

      if (delayMs > 0 && i < toLike.length - 1) {
        await sleep(delayMs);
      }
    }

    console.log(
      `[linerLikeHelper] Готово! Лайкнуто: ${likedCount}, пропущено (уже были): ${skippedCount}, ошибок: ${failed.length}.`,
    );

    if (failed.length > 0) {
      console.warn("[linerLikeHelper] Не удалось лайкнуть следующие треки:");
      console.table(failed);
    }

    if (deletePlaylistAfter && !dryRun && !abortRequested && failed.length === 0) {
      console.log(`[linerLikeHelper] Удаляем исходный плейлист "${playlistTitle}"...`);
      await apiRequest(`/v1/me/playlists/${encodeURIComponent(target.id)}`, {
        method: "DELETE",
      });
      console.log("[linerLikeHelper] Исходный плейлист удалён.");
    }

    if (!dryRun && likedCount > 0) {
      console.log(
        "[linerLikeHelper] Нажмите Ctrl+R (или перезапустите Liner), чтобы список «Лайкнутые» обновился в интерфейсе.",
      );
    }

    return { total: tracks.length, liked: likedCount, skipped: skippedCount, failed };
  }

  async function syncOrder(selector, options = {}) {
    const {
      delayMs = 200,
      unlikeDelayMs = 120,
      smartSkip = true,
      dryRun = false,
      deletePlaylistAfter = false,
    } = options;

    abortRequested = false;

    const target = await resolvePlaylist(selector);
    console.log(
      `[linerLikeHelper] Загружаем плейлист "${target.title}" (${target.id}) для синхронизации порядка...`,
    );

    const detail = await apiRequest(`/v1/me/playlists/${encodeURIComponent(target.id)}`);
    const playlistTitle = detail?.playlist?.title || target.title;
    const rawItems = Array.isArray(detail?.items) ? detail.items : [];

    const seen = new Set();
    const tracks = [];
    let localSkipped = 0;

    for (const item of rawItems) {
      const track = item?.track;
      if (!track?.id) continue;
      if (String(track.id).startsWith("local:")) {
        localSkipped += 1;
        continue;
      }
      if (seen.has(track.id)) continue;
      seen.add(track.id);
      tracks.push(track);
    }

    console.log(
      `[linerLikeHelper] В плейлисте "${playlistTitle}" найдено уникальных треков: ${tracks.length}` +
        (localSkipped ? ` (пропущено локальных: ${localSkipped})` : ""),
    );

    if (tracks.length === 0) {
      console.log("[linerLikeHelper] Нечего сортировать.");
      return { total: 0, updated: 0, kept: 0, failed: [] };
    }

    console.log("[linerLikeHelper] Загружаем текущий порядок «Лайкнутых»...");
    const currentAllLiked = await fetchAllLikedOrder();
    const likedSet = new Set(currentAllLiked);
    const playlistIdSet = new Set(tracks.map((t) => t.id));
    const currentPlaylistOrder = currentAllLiked.filter((id) => playlistIdSet.has(id));

    // Находим максимальный «хвост» в конце плейлиста, который уже стоит в правильном порядке
    let keptSuffix = 0;
    if (smartSkip) {
      let pIdx = tracks.length - 1;
      let cIdx = currentPlaylistOrder.length - 1;
      while (pIdx >= 0 && cIdx >= 0 && tracks[pIdx].id === currentPlaylistOrder[cIdx]) {
        keptSuffix += 1;
        pIdx -= 1;
        cIdx -= 1;
      }
    }

    // Всё, что выше совпадающего хвоста, перелайкиваем с конца к началу
    const toProcess = tracks.slice(0, tracks.length - keptSuffix).reverse();

    console.log(
      `[linerLikeHelper] Уже на своих местах (снизу): ${keptSuffix}, нужно переставить/добавить: ${toProcess.length}` +
        (dryRun ? " [DRY RUN — изменения НЕ вносятся]" : ""),
    );

    if (toProcess.length === 0) {
      console.log("[linerLikeHelper] Порядок уже полностью совпадает с плейлистом!");
      return { total: tracks.length, updated: 0, kept: keptSuffix, failed: [] };
    }

    let updatedCount = 0;
    const failed = [];

    for (let i = 0; i < toProcess.length; i++) {
      if (abortRequested) {
        console.warn(`[linerLikeHelper] Остановлено пользователем на ${i}/${toProcess.length}.`);
        break;
      }

      const track = toProcess[i];
      const label = formatTrack(track);
      const wasLiked = likedSet.has(track.id);

      if (dryRun) {
        console.log(
          `[${i + 1}/${toProcess.length}] [dryRun] ${wasLiked ? "перелайкнуть" : "лайкнуть"}: ${label}`,
        );
        updatedCount += 1;
        continue;
      }

      try {
        if (wasLiked) {
          await apiRequest(`/v1/me/library/tracks/${encodeURIComponent(track.id)}`, {
            method: "DELETE",
          });
          if (unlikeDelayMs > 0) await sleep(unlikeDelayMs);
        }

        await apiRequest(`/v1/me/library/tracks/${encodeURIComponent(track.id)}`, {
          method: "PUT",
          body: { track, trackSnapshot: track },
        });
        updatedCount += 1;
        console.log(
          `[${i + 1}/${toProcess.length}] ✓ ${wasLiked ? "переставлен" : "добавлен"}: ${label}`,
        );
      } catch (err) {
        failed.push({ id: track.id, label, error: err?.message || String(err) });
        console.error(`[${i + 1}/${toProcess.length}] ✗ ${label}:`, err?.message || err);
      }

      if (delayMs > 0 && i < toProcess.length - 1) {
        await sleep(delayMs);
      }
    }

    console.log(
      `[linerLikeHelper] Готово! Переставлено/добавлено: ${updatedCount}, оставлено на месте: ${keptSuffix}, ошибок: ${failed.length}.`,
    );

    if (failed.length > 0) {
      console.warn("[linerLikeHelper] Ошибки при обработке треков:");
      console.table(failed);
    }

    if (deletePlaylistAfter && !dryRun && !abortRequested && failed.length === 0) {
      console.log(`[linerLikeHelper] Удаляем исходный плейлист "${playlistTitle}"...`);
      await apiRequest(`/v1/me/playlists/${encodeURIComponent(target.id)}`, {
        method: "DELETE",
      });
      console.log("[linerLikeHelper] Исходный плейлист удалён.");
    }

    if (!dryRun && updatedCount > 0) {
      console.log(
        "[linerLikeHelper] Нажмите Ctrl+R (или перезапустите Liner), чтобы список «Лайкнутые» обновился в новом порядке.",
      );
    }

    return { total: tracks.length, updated: updatedCount, kept: keptSuffix, failed };
  }

  async function syncCurrentOrder(options = {}) {
    const id = getCurrentPlaylistId();
    if (!id) {
      throw new Error(
        "Сейчас не открыт ни один плейлист. Откройте нужный плейлист в Liner или используйте linerLikeHelper.syncOrder(1).",
      );
    }
    return syncOrder(id, options);
  }

  async function transferCurrent(options = {}) {
    const id = getCurrentPlaylistId();
    if (!id) {
      throw new Error(
        "Сейчас не открыт ни один плейлист. Откройте нужный плейлист в Liner или используйте linerLikeHelper.transfer(номер_или_название).",
      );
    }
    return transfer(id, options);
  }

  window.linerLikeHelper = {
    list: listPlaylists,
    transfer,
    transferCurrent,
    syncOrder,
    syncCurrentOrder,
    stop() {
      abortRequested = true;
      console.warn("[linerLikeHelper] Запрошена остановка...");
    },
  };

  console.log("[linerLikeHelper] Загружен! Ваши плейлисты:");
  await listPlaylists();
  console.log(
    [
      "Примеры команд:",
      "  await linerLikeHelper.transfer(1)                      // перенести плейлист №1 в «Лайкнутые»",
      "  await linerLikeHelper.syncOrder(1)                     // расставить треки в «Лайкнутых» строго в порядке плейлиста №1",
      '  await linerLikeHelper.transfer("Название плейлиста")   // перенести по названию или ID',
      "  await linerLikeHelper.transferCurrent()                // перенести открытый сейчас плейлист",
      "  await linerLikeHelper.syncCurrentOrder()               // синхронизировать порядок открытого плейлиста",
      "  await linerLikeHelper.syncOrder(1, { dryRun: true })   // тестовый прогон без изменений",
      "  linerLikeHelper.stop()                                 // остановить процесс",
    ].join("\n"),
  );
})();

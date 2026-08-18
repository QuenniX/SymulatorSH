import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createBatch, listTemplates } from '../api';
import type { BatchCreateResponse, TemplateSummary } from '../types';

/**
 * Partia testow - uruchamia wiele testow naraz z wybranych szablonow.
 *
 * Layout:
 *  1. Grid "Profile gospodarstw domowych" - szablony pasujace do wzorca
 *     "Profil X: NAZWA - SEZON" grupowane po NAZWIE, kolumny to 4 sezony.
 *     Slowo "Profil" jest ukryte w UI (na prosbe promotora), zamiast tego
 *     nazwy typu "Profil A: Singiel-biuro".
 *  2. Sekcja "Wlasne szablony" - reszta szablonow (dodane przez uzytkownika
 *     w kreatorze). Wyswietlane jako lista z checkboxami.
 *
 * W bazie nazwy szablonow pozostaly bez zmian ("Profil X: ..."). Parsujemy
 * je tylko przy wyswietlaniu - nie robimy migracji SQL.
 */

/** Wzorzec ktory rozpoznaje standardowy szablon: "Profil A: Singiel-biuro - Zima". */
const ARCH_PATTERN = /^Profil\s+([A-Z]):\s+(.+?)\s+-\s+(Zima|Wiosna|Lato|Jesien|Jesień)$/i;

const SEASONS = [
  { code: 'Zima',   label: 'Zima',   icon: '❄',  color: 'text-cyan-300',   bg: 'bg-cyan-950/40 border-cyan-700' },
  { code: 'Wiosna', label: 'Wiosna', icon: '🌸', color: 'text-green-300',  bg: 'bg-green-950/40 border-green-700' },
  { code: 'Lato',   label: 'Lato',   icon: '☀',  color: 'text-yellow-300', bg: 'bg-yellow-950/40 border-yellow-700' },
  { code: 'Jesien', label: 'Jesień', icon: '🍂', color: 'text-orange-300', bg: 'bg-orange-950/40 border-orange-700' },
] as const;

type SeasonCode = typeof SEASONS[number]['code'];

interface ArchGroup {
  /** Kod profilu (A, B, C, ...). */
  code: string;
  /** Nazwa wlasciwa profilu (np. "Singiel-biuro"). */
  label: string;
  /** Mapa sezon -> szablon (albo null jesli brakuje). */
  seasons: Record<SeasonCode, TemplateSummary | null>;
}

export default function BatchRunnerPage() {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<TemplateSummary[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [durationDays, setDurationDays] = useState(7);
  const [speedFactor, setSpeedFactor] = useState(720);
  const [emitEveryN, setEmitEveryN] = useState(5);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<BatchCreateResponse | null>(null);

  useEffect(() => {
    listTemplates().then(setTemplates).catch((e) => setError(String(e)));
  }, []);

  // Podzial szablonow: (1) grupy profili wg wzorca "Profil X: NAZWA - SEZON",
  // (2) wlasne szablony (wszystko co nie pasuje).
  const { archGroups, customTemplates } = useMemo(() => {
    const groupsByCode = new Map<string, ArchGroup>();
    const custom: TemplateSummary[] = [];

    for (const t of templates) {
      const m = t.name.match(ARCH_PATTERN);
      if (!m) {
        custom.push(t);
        continue;
      }
      const [, code, label, seasonRaw] = m;
      const season: SeasonCode = seasonRaw.toLowerCase().startsWith('jesie') ? 'Jesien' : (seasonRaw as SeasonCode);

      if (!groupsByCode.has(code)) {
        groupsByCode.set(code, {
          code,
          label: label.trim(),
          seasons: { Zima: null, Wiosna: null, Lato: null, Jesien: null },
        });
      }
      groupsByCode.get(code)!.seasons[season] = t;
    }

    const sorted = Array.from(groupsByCode.values()).sort((a, b) => a.code.localeCompare(b.code));
    return { archGroups: sorted, customTemplates: custom };
  }, [templates]);

  function toggle(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function selectAll() {
    setSelectedIds(new Set(templates.map((t) => t.templateId)));
  }

  function clearAll() {
    setSelectedIds(new Set());
  }

  /** Zaznacz wszystkie profile w danym sezonie (kolumna gridu). */
  function selectSeason(season: SeasonCode) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      for (const g of archGroups) {
        const t = g.seasons[season];
        if (t) next.add(t.templateId);
      }
      return next;
    });
  }

  /** Zaznacz wszystkie sezony jednego profilu (wiersz gridu). */
  function selectProfile(code: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      const g = archGroups.find((x) => x.code === code);
      if (g) {
        for (const t of Object.values(g.seasons)) {
          if (t) next.add(t.templateId);
        }
      }
      return next;
    });
  }

  // Ile szablonow z danego sezonu jest dostepnych (dla podpowiedzi "wszystkie Zima ×6")
  function seasonCount(season: SeasonCode): number {
    return archGroups.filter((g) => g.seasons[season] !== null).length;
  }

  // Szacowany czas i punkty w Influx
  const estimates = useMemo(() => {
    const perTestMin = Math.max(1, Math.round((durationDays * 24 * 60) / speedFactor));
    const numTests = selectedIds.size;
    const POOL_SIZE = 4;
    const batchWaves = Math.max(1, Math.ceil(numTests / POOL_SIZE));
    const totalMin = batchWaves * perTestMin;
    const avgDevices = 14;
    const emissions = Math.floor((durationDays * 1440) / emitEveryN);
    const totalPoints = numTests * avgDevices * emissions;
    return { perTestMin, totalMin, numTests, totalPoints, poolSize: POOL_SIZE };
  }, [selectedIds, durationDays, speedFactor, emitEveryN]);

  async function handleRun() {
    if (selectedIds.size === 0) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const prefix = `[Partia ${new Date().toISOString().substring(0, 16)}]`;
      const response = await createBatch({
        templateIds: Array.from(selectedIds),
        durationDays,
        speedFactor,
        emitEveryNMinutes: emitEveryN,
        namePrefix: prefix,
      });
      setResult(response);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Błąd tworzenia partii: ${msg}`);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <Link to="/" className="text-brand-500 hover:underline text-sm">← Powrót do listy</Link>

      <h1 className="text-2xl font-bold text-slate-100 mt-2 mb-1">Partia testów</h1>
      <p className="text-sm text-slate-400 mb-6">
        Zaznacz profile i sezony do uruchomienia. Backend wykona <strong>{estimates.poolSize} testów równolegle</strong>, reszta czeka w kolejce.
      </p>

      {error && (
        <div className="bg-red-900/40 border border-red-800 text-red-200 p-3 rounded mb-4 text-sm">
          {error}
        </div>
      )}

      {/* === SEKCJA 1: Profile gospodarstw domowych === */}
      {archGroups.length > 0 && (
        <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-4">
          <div className="flex items-center justify-between flex-wrap gap-2 mb-3">
            <div>
              <h2 className="text-lg font-semibold text-slate-100">
                🏠 Profile gospodarstw domowych
              </h2>
              <p className="text-xs text-slate-400 mt-0.5">
                {archGroups.length} profili × 4 sezony = {archGroups.length * 4} możliwych testów
              </p>
            </div>
            <div className="flex gap-2 flex-wrap">
              <button type="button" onClick={selectAll}
                className="text-xs px-3 py-1.5 rounded bg-brand-700 hover:bg-brand-600 text-white font-medium">
                Zaznacz wszystkie
              </button>
              <button type="button" onClick={clearAll}
                className="text-xs px-3 py-1.5 rounded bg-slate-700 hover:bg-slate-600 text-slate-200">
                Wyczyść
              </button>
            </div>
          </div>

          {/* Szybki wybór per sezon */}
          <div className="flex gap-2 mb-3 flex-wrap">
            {SEASONS.map((s) => (
              <button key={s.code} type="button" onClick={() => selectSeason(s.code)}
                className={`text-xs px-3 py-1.5 rounded border ${s.bg} ${s.color} hover:brightness-125 transition`}>
                {s.icon} + wszystkie {s.label} (×{seasonCount(s.code)})
              </button>
            ))}
          </div>

          {/* Grid: profil (wiersz) × sezon (kolumna) */}
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-700">
                  <th className="text-left py-2 px-2 text-xs uppercase text-slate-400 font-semibold">Profil</th>
                  {SEASONS.map((s) => (
                    <th key={s.code} className={`text-center py-2 px-2 text-xs uppercase font-semibold ${s.color}`}>
                      {s.icon} {s.label}
                    </th>
                  ))}
                  <th className="text-center py-2 px-2 text-xs uppercase text-slate-400 font-semibold">Cały profil</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                {archGroups.map((g) => {
                  const groupSelectedCount = Object.values(g.seasons).filter((t) => t && selectedIds.has(t.templateId)).length;
                  return (
                    <tr key={g.code} className="hover:bg-slate-900/50">
                      <td className="py-2 px-2">
                        <div className="text-slate-200 font-medium">
                          <span className="text-slate-500 mr-2">{g.code}</span>
                          {g.label}
                        </div>
                      </td>
                      {SEASONS.map((s) => {
                        const t = g.seasons[s.code];
                        const isSelected = t ? selectedIds.has(t.templateId) : false;
                        return (
                          <td key={s.code} className="text-center py-2 px-2">
                            {t ? (
                              <label className="cursor-pointer inline-flex items-center justify-center">
                                <input
                                  type="checkbox"
                                  checked={isSelected}
                                  onChange={() => toggle(t.templateId)}
                                  className="w-5 h-5 accent-brand-500 cursor-pointer"
                                />
                              </label>
                            ) : (
                              <span className="text-slate-600 text-xs">—</span>
                            )}
                          </td>
                        );
                      })}
                      <td className="text-center py-2 px-2">
                        <button type="button" onClick={() => selectProfile(g.code)}
                          className={`text-xs px-2 py-1 rounded border transition ${
                            groupSelectedCount === 4
                              ? 'bg-brand-800 border-brand-600 text-brand-100'
                              : 'bg-slate-900 hover:bg-slate-700 border-slate-700 text-slate-300'
                          }`}>
                          {groupSelectedCount === 4 ? '✓ 4/4' : `+ wszystkie (${groupSelectedCount}/4)`}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* === SEKCJA 2: Własne szablony === */}
      {customTemplates.length > 0 && (
        <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-lg font-semibold text-slate-100">
              🔧 Własne szablony
              <span className="ml-2 text-sm text-slate-400 font-normal">({customTemplates.length})</span>
            </h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
            {customTemplates.map((t) => {
              const isSelected = selectedIds.has(t.templateId);
              return (
                <label key={t.templateId}
                  className={`flex items-start gap-2 px-3 py-2 rounded border cursor-pointer transition ${
                    isSelected
                      ? 'bg-brand-900 border-brand-500'
                      : 'bg-slate-950 border-slate-700 hover:bg-slate-900'
                  }`}>
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={() => toggle(t.templateId)}
                    className="mt-1 w-4 h-4 accent-brand-500 cursor-pointer"
                  />
                  <div className="min-w-0 flex-1">
                    <div className={`text-sm font-medium truncate ${isSelected ? 'text-white' : 'text-slate-200'}`}>
                      {t.name}
                    </div>
                    {t.description && (
                      <div className="text-xs text-slate-400 mt-0.5 truncate">
                        {t.description}
                      </div>
                    )}
                  </div>
                </label>
              );
            })}
          </div>
        </div>
      )}

      {/* Fallback jesli nie ma zadnych szablonow */}
      {archGroups.length === 0 && customTemplates.length === 0 && (
        <div className="bg-slate-800 border border-slate-700 rounded p-8 text-center mb-4">
          <p className="text-slate-400 text-sm">
            Brak zapisanych szablonów. Utwórz pierwszy w{' '}
            <Link to="/kreator" className="text-brand-500 hover:underline">kreatorze</Link>.
          </p>
        </div>
      )}

      {/* === Parametry testów === */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-4">
        <h2 className="text-lg font-semibold text-slate-100 mb-3">Parametry testów</h2>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <label className="block">
            <span className="text-xs text-slate-400">Dni symulacji: <strong className="text-slate-100">{durationDays}</strong></span>
            <input type="range" min="1" max="30" value={durationDays}
              onChange={(e) => setDurationDays(Number(e.target.value))}
              className="w-full mt-2 accent-brand-500" />
            <div className="flex justify-between text-xs text-slate-500 mt-1">
              <span>1 (smoke)</span>
              <span>7</span>
              <span>30 (badania)</span>
            </div>
          </label>

          <label className="block">
            <span className="text-xs text-slate-400">Przyspieszenie: <strong className="text-slate-100">×{speedFactor}</strong></span>
            <div className="flex gap-1 mt-2">
              {[360, 720, 1440, 2160].map((v) => (
                <button key={v} type="button" onClick={() => setSpeedFactor(v)}
                  className={`flex-1 px-2 py-1 text-xs rounded border ${
                    speedFactor === v
                      ? 'bg-brand-600 text-white border-brand-500'
                      : 'bg-slate-950 text-slate-300 border-slate-700 hover:bg-slate-800'
                  }`}>
                  ×{v}
                </button>
              ))}
            </div>
            <div className="text-xs text-slate-500 mt-1">
              ×720: 7d = 14 min · ×1440: 7d = 7 min · ×2160: 7d = ~5 min
            </div>
          </label>

          <label className="block">
            <span className="text-xs text-slate-400">Emisja co N minut: <strong className="text-slate-100">{emitEveryN}</strong></span>
            <input type="range" min="1" max="15" value={emitEveryN}
              onChange={(e) => setEmitEveryN(Number(e.target.value))}
              className="w-full mt-2 accent-brand-500" />
            <div className="flex justify-between text-xs text-slate-500 mt-1">
              <span>1 (max precyzja)</span>
              <span>5 (default)</span>
              <span>15 (lekko)</span>
            </div>
          </label>
        </div>
      </div>

      {/* === Podsumowanie + start === */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="text-sm text-slate-300">
            <div>
              Wybrano: <strong className="text-slate-100">{estimates.numTests}</strong> testów
            </div>
            <div className="text-xs text-slate-400 mt-1">
              Czas realny: ~<strong className="text-slate-200">{estimates.totalMin} min</strong>
              {' '}(pool {estimates.poolSize} = {Math.max(1, Math.ceil(estimates.numTests / estimates.poolSize))} fal × {estimates.perTestMin} min każda)
            </div>
            <div className="text-xs text-slate-400">
              Pomiary w InfluxDB: ~<strong className="text-slate-200">{estimates.totalPoints.toLocaleString('pl-PL')}</strong> punktów
            </div>
          </div>

          <button type="button" onClick={handleRun}
            disabled={loading || estimates.numTests === 0}
            className="px-6 py-3 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 disabled:cursor-not-allowed text-white rounded font-semibold">
            {loading ? 'Tworzenie...' : `▶ Uruchom ${estimates.numTests} testów`}
          </button>
        </div>
      </div>

      {/* === Wynik === */}
      {result && (
        <div className="bg-slate-800 border border-slate-700 rounded p-4 mt-4">
          <h3 className="text-lg font-semibold text-slate-100 mb-2">Wynik partii</h3>
          <div className="text-sm text-slate-300 mb-3">
            Utworzono <strong className="text-green-400">{result.createdCount}</strong> z {result.requestedCount} testów.
            {result.failedCount > 0 && (
              <span className="text-red-400"> Błędy: {result.failedCount}.</span>
            )}
          </div>
          {result.failures.length > 0 && (
            <div className="bg-red-900/30 border border-red-800 rounded p-2 mb-3 text-xs">
              <strong>Błędy:</strong>
              <ul className="mt-1 list-disc list-inside">
                {result.failures.map((f, i) => (
                  <li key={i}>{f.templateName}: {f.errorMessage}</li>
                ))}
              </ul>
            </div>
          )}
          <button type="button" onClick={() => navigate('/')}
            className="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white rounded text-sm">
            Idź do listy testów →
          </button>
        </div>
      )}
    </div>
  );
}

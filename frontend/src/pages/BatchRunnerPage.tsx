import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createBatch, listTemplates } from '../api';
import type { BatchCreateResponse, TemplateSummary } from '../types';

/**
 * Batch runner - uruchamia wiele testow naraz z wybranych szablonow.
 * Grid: 6 archetypow (A-F) × 4 sezony (Zima/Wiosna/Lato/Jesien) = 24 checkboxy.
 * Suwaki: durationDays, speedFactor, emitEveryNMinutes.
 */

const ARCHETYPES = [
  { code: 'A', name: 'Singiel-biuro' },
  { code: 'B', name: 'Remote worker' },
  { code: 'C', name: 'Rodzina 2+2' },
  { code: 'D', name: 'Senior samotny' },
  { code: 'E', name: 'Studenci' },
  { code: 'F', name: 'DINK' },
];

const SEASONS = [
  { code: 'Zima', color: 'text-cyan-300' },
  { code: 'Wiosna', color: 'text-green-300' },
  { code: 'Lato', color: 'text-yellow-300' },
  { code: 'Jesien', color: 'text-orange-300' },
];

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

  /** Znajdź template'y które pasują do "Archetyp {code}: ... - {sezon}". */
  const templateGrid = useMemo(() => {
    const grid: Record<string, Record<string, TemplateSummary | null>> = {};
    for (const arch of ARCHETYPES) {
      grid[arch.code] = {};
      for (const season of SEASONS) {
        const found = templates.find(
          (t) =>
            t.name.startsWith(`Archetyp ${arch.code}:`) &&
            t.name.toLowerCase().endsWith(season.code.toLowerCase()),
        );
        grid[arch.code][season.code] = found ?? null;
      }
    }
    return grid;
  }, [templates]);

  function toggleTemplate(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function selectAll() {
    const all = new Set<string>();
    for (const arch of Object.values(templateGrid)) {
      for (const t of Object.values(arch)) {
        if (t) all.add(t.templateId);
      }
    }
    setSelectedIds(all);
  }

  function clearAll() {
    setSelectedIds(new Set());
  }

  function selectSeason(seasonCode: string) {
    const next = new Set(selectedIds);
    for (const arch of Object.values(templateGrid)) {
      const t = arch[seasonCode];
      if (t) next.add(t.templateId);
    }
    setSelectedIds(next);
  }

  function selectArchetype(archCode: string) {
    const next = new Set(selectedIds);
    for (const t of Object.values(templateGrid[archCode] ?? {})) {
      if (t) next.add(t.templateId);
    }
    setSelectedIds(next);
  }

  // Szacowany czas per test i cały batch
  const estimates = useMemo(() => {
    const perTestMin = Math.round((durationDays * 24 * 60) / speedFactor);
    const numTests = selectedIds.size;
    // Pool = 12, więc czas realny = ceil(numTests / 12) * perTestMin
    const batchWaves = Math.ceil(numTests / 12);
    const totalMin = batchWaves * perTestMin;
    // Ile pomiarów?
    const avgDevices = 14; // orientacyjnie
    const emissions = Math.floor((durationDays * 1440) / emitEveryN);
    const totalPoints = numTests * avgDevices * emissions;
    return { perTestMin, totalMin, numTests, totalPoints };
  }, [selectedIds, durationDays, speedFactor, emitEveryN]);

  async function handleRun() {
    if (selectedIds.size === 0) return;
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const prefix = `[BATCH ${new Date().toISOString().substring(0, 16)}]`;
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
      setError(`Błąd batch: ${msg}`);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <Link to="/" className="text-brand-500 hover:underline text-sm">← Powrót do listy</Link>

      <h1 className="text-2xl font-bold text-slate-100 mt-2 mb-1">Batch runner</h1>
      <p className="text-sm text-slate-400 mb-6">
        Wybierz archetypy do uruchomienia. Backend wykona 12 równolegle (pool-size), reszta czeka w kolejce.
      </p>

      {error && (
        <div className="bg-red-900/40 border border-red-800 text-red-200 p-3 rounded mb-4 text-sm">
          {error}
        </div>
      )}

      {/* Grid archetypów × sezony */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-4">
        <div className="flex items-center justify-between mb-3 flex-wrap gap-2">
          <h2 className="text-lg font-semibold text-slate-100">Wybór archetypów</h2>
          <div className="flex gap-2 flex-wrap">
            <button type="button" onClick={selectAll}
              className="text-xs px-3 py-1 rounded bg-brand-700 hover:bg-brand-600 text-white">
              Zaznacz wszystkie (24)
            </button>
            <button type="button" onClick={clearAll}
              className="text-xs px-3 py-1 rounded bg-slate-700 hover:bg-slate-600 text-slate-200">
              Wyczyść
            </button>
          </div>
        </div>

        {/* Szybki wybór per sezon */}
        <div className="flex gap-2 mb-3 flex-wrap">
          {SEASONS.map((s) => (
            <button key={s.code} type="button" onClick={() => selectSeason(s.code)}
              className={`text-xs px-3 py-1 rounded bg-slate-900 hover:bg-slate-700 border border-slate-700 ${s.color}`}>
              + Cały {s.code} (6)
            </button>
          ))}
        </div>

        {/* Tabela grid */}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-700 text-slate-400 text-xs uppercase">
                <th className="text-left py-2 px-2">Archetyp</th>
                {SEASONS.map((s) => (
                  <th key={s.code} className={`text-center py-2 px-2 ${s.color}`}>{s.code}</th>
                ))}
                <th className="text-center py-2 px-2">Akcja</th>
              </tr>
            </thead>
            <tbody>
              {ARCHETYPES.map((arch) => (
                <tr key={arch.code} className="border-b border-slate-800 hover:bg-slate-900">
                  <td className="py-2 px-2 text-slate-200">
                    <strong>{arch.code}</strong> · {arch.name}
                  </td>
                  {SEASONS.map((season) => {
                    const t = templateGrid[arch.code]?.[season.code];
                    const isSelected = t ? selectedIds.has(t.templateId) : false;
                    return (
                      <td key={season.code} className="text-center py-2 px-2">
                        {t ? (
                          <input type="checkbox" checked={isSelected}
                            onChange={() => toggleTemplate(t.templateId)}
                            className="w-5 h-5 accent-brand-500 cursor-pointer" />
                        ) : (
                          <span className="text-slate-600 text-xs">brak</span>
                        )}
                      </td>
                    );
                  })}
                  <td className="text-center py-2 px-2">
                    <button type="button" onClick={() => selectArchetype(arch.code)}
                      className="text-xs px-2 py-1 rounded bg-slate-900 hover:bg-slate-700 text-slate-300 border border-slate-700">
                      + wszystkie sezony
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Parametry testów */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-4">
        <h2 className="text-lg font-semibold text-slate-100 mb-3">Parametry testów</h2>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {/* Duration days */}
          <label className="block">
            <span className="text-xs text-slate-400">Dni symulacji: <strong className="text-slate-100">{durationDays}</strong></span>
            <input type="range" min="1" max="30" value={durationDays}
              onChange={(e) => setDurationDays(Number(e.target.value))}
              className="w-full mt-2 accent-brand-500" />
            <div className="flex justify-between text-xs text-slate-500 mt-1">
              <span>1 (smoke)</span>
              <span>7</span>
              <span>30 (production)</span>
            </div>
          </label>

          {/* Speed factor */}
          <label className="block">
            <span className="text-xs text-slate-400">Speed factor: <strong className="text-slate-100">×{speedFactor}</strong></span>
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

          {/* Emit every N minutes */}
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

      {/* Podsumowanie + start */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="text-sm text-slate-300">
            <div>
              Wybrano: <strong className="text-slate-100">{estimates.numTests}</strong> testów
            </div>
            <div className="text-xs text-slate-400 mt-1">
              Czas realny: ~<strong className="text-slate-200">{estimates.totalMin} min</strong>
              {' '}(pool 12 = {Math.ceil(estimates.numTests / 12)} fal × {estimates.perTestMin} min każda)
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

      {/* Wynik */}
      {result && (
        <div className="bg-slate-800 border border-slate-700 rounded p-4 mt-4">
          <h3 className="text-lg font-semibold text-slate-100 mb-2">Wynik batch</h3>
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

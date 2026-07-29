import { useEffect, useMemo, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { getProjectedCosts } from '../api';
import type { ProjectedCostBreakdown, TariffCode } from '../types';

type CostTab = 'actual' | 'projection';
type Season = 'Zima' | 'Wiosna' | 'Lato' | 'Jesien';

interface Props {
  testId: string;
  /** Nazwa testu (np. "Archetyp F: DINK - Zima") - do wykrycia sezonu. */
  testName: string;
  /** Liczba dni testu - dla "Test rzeczywisty" liczymy okres tej dlugosci. */
  durationDays: number;
  reloadKey?: number;
}

const TARIFF_COLORS: Record<TariffCode, string> = {
  G11: '#3b82f6',
  G12: '#8b5cf6',
  RDN: '#f59e0b',
};

const TARIFF_LABELS: Record<TariffCode, string> = {
  G11: 'G11 (stały cennik)',
  G12: 'G12 (dzień/noc)',
  RDN: 'RDN (dynamiczny)',
};

// Miesiące per sezon (1=styczeń, 12=grudzień)
const SEASON_MONTHS: Record<Season, number[]> = {
  Zima:   [12, 1, 2],
  Wiosna: [3, 4, 5],
  Lato:   [6, 7, 8],
  Jesien: [9, 10, 11],
};

const SEASON_COLORS: Record<Season, string> = {
  Zima:   'text-cyan-300',
  Wiosna: 'text-green-300',
  Lato:   'text-yellow-300',
  Jesien: 'text-orange-300',
};

/** Rozpoznaj sezon z nazwy testu (np. "Archetyp F: DINK - Zima" -> Zima). */
function extractSeason(testName: string): Season | null {
  const n = testName.toLowerCase();
  if (n.includes('zima')) return 'Zima';
  if (n.includes('wiosna')) return 'Wiosna';
  if (n.includes('lato')) return 'Lato';
  if (n.includes('jesien') || n.includes('jesień')) return 'Jesien';
  return null;
}

/** Sprawdz czy zakres dat pasuje do sezonu (wszystkie miesiące w zakresie są sezonowe). */
function isDateRangeInSeason(from: string, to: string, season: Season): boolean {
  const seasonMonths = new Set(SEASON_MONTHS[season]);
  const start = new Date(from);
  const end = new Date(to);
  const cursor = new Date(start);
  while (cursor <= end) {
    if (!seasonMonths.has(cursor.getMonth() + 1)) return false;
    cursor.setDate(cursor.getDate() + 7); // sample raz na tydzień, wystarczy
  }
  return true;
}

/** Ostatni dzień miesiąca w YYYY-MM-DD */
function lastDayOfMonth(year: number, month: number): string {
  const d = new Date(year, month, 0); // month = 1-12, day 0 = last day of previous
  return d.toISOString().split('T')[0];
}

/** Pierwszy dzień miesiąca w YYYY-MM-DD */
function firstDayOfMonth(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, '0')}-01`;
}

const MONTH_NAMES = ['sty', 'lut', 'mar', 'kwi', 'maj', 'cze', 'lip', 'sie', 'wrz', 'paź', 'lis', 'gru'];

/** Wygeneruj presety sezonowe dla ostatnich 3 lat. */
function generateSeasonalPresets(season: Season, currentYear: number): Array<{ label: string; from: string; to: string }> {
  const presets: Array<{ label: string; from: string; to: string }> = [];

  // Pełne sezony (od najstarszego)
  for (let year = currentYear - 2; year <= currentYear; year++) {
    if (season === 'Zima') {
      // Zima YYYY = grudzień YYYY-1 do luty YYYY
      const from = firstDayOfMonth(year - 1, 12);
      const to = lastDayOfMonth(year, 2);
      presets.push({ label: `Cała zima ${year}`, from, to });
    } else {
      const months = SEASON_MONTHS[season];
      const from = firstDayOfMonth(year, months[0]);
      const to = lastDayOfMonth(year, months[months.length - 1]);
      presets.push({ label: `Całe ${season.toLowerCase()} ${year}`, from, to });
    }
  }

  // Poszczególne miesiące dla ostatnich 3 lat (tylko sensowne miesiące)
  const months = SEASON_MONTHS[season];
  for (let year = currentYear - 2; year <= currentYear; year++) {
    for (const m of months) {
      // Dla zimy grudzień to poprzedni rok
      const monthYear = (season === 'Zima' && m === 12) ? year - 1 : year;
      const from = firstDayOfMonth(monthYear, m);
      const to = lastDayOfMonth(monthYear, m);
      const label = `${MONTH_NAMES[m - 1]} ${monthYear}`;
      // Nie dodawaj duplikatów
      if (!presets.some(p => p.from === from && p.to === to)) {
        presets.push({ label, from, to });
      }
    }
  }

  return presets;
}

/**
 * Sekcja "Koszt" na stronie testu z rozpoznaniem sezonu.
 */
export default function CostSection({ testId, testName, durationDays, reloadKey }: Props) {
  const [tab, setTab] = useState<CostTab>('actual');
  const season = useMemo(() => extractSeason(testName), [testName]);

  return (
    <div className="bg-slate-800 border border-slate-700 rounded p-4">
      <div className="flex items-center justify-between flex-wrap gap-2 mb-4 border-b border-slate-700 pb-3">
        <div className="flex items-center gap-2">
          <TabButton
            active={tab === 'actual'}
            onClick={() => setTab('actual')}
            label="Test rzeczywisty"
            tooltip={`Koszt profilu testu × ceny RDN z okresu ${durationDays} dni (do wybranej daty)`}
          />
          <TabButton
            active={tab === 'projection'}
            onClick={() => setTab('projection')}
            label="Projekcja historyczna"
            tooltip="Profil zużycia × ceny RDN z wybranego okresu"
          />
        </div>
        {season && (
          <div className={`text-xs ${SEASON_COLORS[season]} bg-slate-900 border border-slate-700 rounded px-3 py-1`}>
            Wykryto sezon: <strong>{season}</strong> · miesiące: {SEASON_MONTHS[season].map(m => MONTH_NAMES[m - 1]).join(', ')}
          </div>
        )}
      </div>

      {tab === 'actual'
        ? <ActualCostView testId={testId} durationDays={durationDays} season={season} reloadKey={reloadKey} />
        : <ProjectionView testId={testId} season={season} />
      }
    </div>
  );
}

// ==========================================================================
//  Tab button
// ==========================================================================

function TabButton({
  active, onClick, label, tooltip,
}: { active: boolean; onClick: () => void; label: string; tooltip?: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title={tooltip}
      className={`px-4 py-2 text-sm font-medium rounded-t transition ${
        active
          ? 'bg-slate-700 text-white border-b-2 border-brand-500'
          : 'text-slate-400 hover:text-slate-100 hover:bg-slate-700/50'
      }`}
    >
      {label}
    </button>
  );
}

// ==========================================================================
//  ActualCostView
// ==========================================================================

function ActualCostView({
  testId, durationDays, season, reloadKey,
}: { testId: string; durationDays: number; season: Season | null; reloadKey?: number }) {
  // Domyslna data koncowa: koniec ostatniego pelnego okresu sezonu lub wczoraj.
  const defaultEndDate = useMemo(() => {
    if (season) {
      const now = new Date();
      const currentMonth = now.getMonth() + 1;
      const currentYear = now.getFullYear();
      const seasonMonths = SEASON_MONTHS[season];
      // Znajdz ostatni miniony miesiąc sezonu
      // Dla zimy specjalnie - luty currentYear jeśli już przeszedł
      if (season === 'Zima') {
        if (currentMonth > 2) return lastDayOfMonth(currentYear, 2);
        if (currentMonth === 1 || currentMonth === 2) return lastDayOfMonth(currentYear - 1, 2);
        return lastDayOfMonth(currentYear - 1, 2);
      }
      // Dla innych sezonów: ostatni miesiąc sezonu w tym roku jeśli minął, else poprzedni rok
      const lastMonth = seasonMonths[seasonMonths.length - 1];
      if (currentMonth > lastMonth) return lastDayOfMonth(currentYear, lastMonth);
      return lastDayOfMonth(currentYear - 1, lastMonth);
    }
    // Bez sezonu: wczoraj
    const d = new Date();
    d.setDate(d.getDate() - 1);
    return d.toISOString().split('T')[0];
  }, [season]);

  const [endDate, setEndDate] = useState(defaultEndDate);
  const [cost, setCost] = useState<ProjectedCostBreakdown | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fromDate = useMemo(() => {
    const d = new Date(endDate);
    d.setDate(d.getDate() - durationDays + 1);
    return d.toISOString().split('T')[0];
  }, [endDate, durationDays]);

  // Ostrzeżenie: czy zakres nie pasuje do sezonu?
  const rangeMismatch = season && !isDateRangeInSeason(fromDate, endDate, season);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    getProjectedCosts(testId, fromDate, endDate)
      .then((data) => { if (!cancelled) setCost(data); })
      .catch((err: unknown) => {
        if (!cancelled) {
          const msg = err instanceof Error ? err.message : String(err);
          setError(`Błąd ładowania kosztów: ${msg}`);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [testId, fromDate, endDate, reloadKey]);

  // Presety sezonowe: przesuwamy datę końcową na ostatni dzień typowych miesięcy sezonu
  const currentYear = new Date().getFullYear();
  const monthlyPresets = useMemo(() => {
    if (!season) return [];
    const presets: Array<{ label: string; endDate: string }> = [];
    const months = SEASON_MONTHS[season];
    for (let year = currentYear - 2; year <= currentYear; year++) {
      for (const m of months) {
        const monthYear = (season === 'Zima' && m === 12) ? year - 1 : year;
        const end = lastDayOfMonth(monthYear, m);
        // Nie proponuj miesięcy w przyszłości
        if (new Date(end) > new Date()) continue;
        presets.push({ label: `${MONTH_NAMES[m - 1]} ${monthYear}`, endDate: end });
      }
    }
    return presets;
  }, [season, currentYear]);

  const summaryChartData = useMemo(() => {
    if (!cost) return [];
    return [
      { tariff: 'G11', label: TARIFF_LABELS.G11, cost: cost.totalCostG11Pln, code: 'G11' as TariffCode },
      { tariff: 'G12', label: TARIFF_LABELS.G12, cost: cost.totalCostG12Pln, code: 'G12' as TariffCode },
      { tariff: 'RDN', label: TARIFF_LABELS.RDN, cost: cost.totalCostRdnPln, code: 'RDN' as TariffCode },
    ];
  }, [cost]);

  return (
    <div>
      {/* Panel wyboru daty koncowej */}
      <div className="bg-slate-950 border border-slate-700 rounded p-3 mb-4">
        <div className="text-xs text-slate-400 mb-2">
          Wybierz datę <strong className="text-slate-200">końcową</strong> okresu.
          Backend weźmie ceny RDN z <strong>{durationDays} dni</strong> wstecz od tej daty
          i policzy koszt profilu tego testu w tym okresie.
        </div>
        <div className="flex items-end gap-3 flex-wrap mb-2">
          <label className="text-xs text-slate-400">
            Data końcowa
            <input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)}
              className="mt-1 block px-3 py-2 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500" />
          </label>
          <div className="text-xs text-slate-400 pb-1">
            → okres: <strong className="text-slate-200">{fromDate}</strong> — <strong className="text-slate-200">{endDate}</strong>
            {' '}({durationDays} dni)
          </div>
        </div>

        {/* Presety sezonowe */}
        {season && monthlyPresets.length > 0 && (
          <div className="mt-3 pt-3 border-t border-slate-800">
            <div className="text-xs text-slate-500 mb-2">
              Presety sezonowe (koniec miesiąca <span className={SEASON_COLORS[season]}>{season}</span>):
            </div>
            <div className="flex gap-2 flex-wrap">
              {monthlyPresets.map((p) => (
                <button key={p.endDate} type="button" onClick={() => setEndDate(p.endDate)}
                  className={`text-xs px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 border border-slate-700 ${
                    endDate === p.endDate ? 'text-white bg-brand-700 border-brand-500' : 'text-slate-300'
                  }`}>
                  {p.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Fallback presety dla testów bez sezonu */}
        {!season && (
          <div className="mt-3 pt-3 border-t border-slate-800">
            <div className="text-xs text-slate-500 mb-2">Presety:</div>
            <div className="flex gap-2 flex-wrap">
              {[
                { label: 'Wczoraj', offset: 1 },
                { label: 'Tydzień temu', offset: 7 },
                { label: 'Miesiąc temu', offset: 30 },
                { label: '3 miesiące temu', offset: 90 },
              ].map((p) => (
                <button key={p.label} type="button"
                  onClick={() => {
                    const d = new Date();
                    d.setDate(d.getDate() - p.offset);
                    setEndDate(d.toISOString().split('T')[0]);
                  }}
                  className="text-xs px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700">
                  {p.label}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {rangeMismatch && (
        <div className="text-sm text-yellow-300 bg-yellow-900/30 border border-yellow-800 rounded p-2 mb-3">
          ⚠️ Uwaga: to profil <strong>{season}</strong>, a wybrany zakres zawiera miesiące spoza sezonu.
          Wyniki mogą być niereprezentatywne (np. grzejnik odpalany w upale).
        </div>
      )}

      {error && (
        <div className="text-sm text-red-300 bg-red-900/40 border border-red-800 rounded p-2 mb-3">{error}</div>
      )}

      {loading && !cost && <p className="text-slate-400 text-sm">Ładowanie...</p>}

      {cost && cost.daysInPeriod === 0 && (
        <p className="text-slate-400 text-sm py-4">Brak danych zużycia w tym teście.</p>
      )}

      {cost && cost.daysInPeriod > 0 && (
        <CostResultView cost={cost} summaryChartData={summaryChartData} />
      )}
    </div>
  );
}

// ==========================================================================
//  ProjectionView - z presetami sezonowymi
// ==========================================================================

function ProjectionView({ testId, season }: { testId: string; season: Season | null }) {
  const currentYear = new Date().getFullYear();
  const defaultTo = useMemo(() => new Date().toISOString().split('T')[0], []);
  const defaultFrom = useMemo(() => {
    const d = new Date();
    d.setFullYear(d.getFullYear() - 1);
    return d.toISOString().split('T')[0];
  }, []);

  const [from, setFrom] = useState(defaultFrom);
  const [to, setTo] = useState(defaultTo);
  const [proj, setProj] = useState<ProjectedCostBreakdown | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showExpert, setShowExpert] = useState(false);

  async function runProjection() {
    setLoading(true);
    setError(null);
    try {
      const data = await getProjectedCosts(testId, from, to);
      setProj(data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Błąd projekcji: ${msg}`);
    } finally {
      setLoading(false);
    }
  }

  // Presety sezonowe (jeśli sezon rozpoznany)
  const seasonalPresets = useMemo(() => {
    if (!season) return [];
    return generateSeasonalPresets(season, currentYear);
  }, [season, currentYear]);

  // Stare "eksperckie" presety (całe lata, 12mies)
  const expertPresets = [
    { label: 'Cały 2024', from: '2024-01-01', to: '2024-12-31' },
    { label: 'Cały 2025', from: '2025-01-01', to: '2025-12-31' },
    { label: 'Cały 2026 (do dziś)', from: '2026-01-01', to: defaultTo },
    { label: 'Ostatnie 12 miesięcy', from: defaultFrom, to: defaultTo },
    { label: '2024-2025 (2 lata)', from: '2024-01-01', to: '2025-12-31' },
  ];

  const rangeMismatch = season && !isDateRangeInSeason(from, to, season);

  const summaryChartData = useMemo(() => {
    if (!proj) return [];
    return [
      { tariff: 'G11', label: TARIFF_LABELS.G11, cost: proj.totalCostG11Pln, code: 'G11' as TariffCode },
      { tariff: 'G12', label: TARIFF_LABELS.G12, cost: proj.totalCostG12Pln, code: 'G12' as TariffCode },
      { tariff: 'RDN', label: TARIFF_LABELS.RDN, cost: proj.totalCostRdnPln, code: 'RDN' as TariffCode },
    ];
  }, [proj]);

  return (
    <div>
      <div className="bg-slate-950 border border-slate-700 rounded p-3 mb-4">
        <div className="flex items-end gap-3 flex-wrap mb-3">
          <label className="text-xs text-slate-400">
            Od
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)}
              className="mt-1 block px-3 py-2 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500" />
          </label>
          <label className="text-xs text-slate-400">
            Do
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)}
              className="mt-1 block px-3 py-2 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500" />
          </label>
          <button type="button" onClick={runProjection} disabled={loading}
            className="px-4 py-2 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 text-white rounded text-sm font-medium">
            {loading ? 'Liczenie...' : 'Policz projekcję'}
          </button>
        </div>

        {/* Presety sezonowe (jeśli sezon rozpoznany) */}
        {season && seasonalPresets.length > 0 && (
          <div className="mt-2">
            <div className="text-xs text-slate-500 mb-2">
              Presety sezonowe (<span className={SEASON_COLORS[season]}>{season}</span>):
            </div>
            <div className="flex gap-2 flex-wrap">
              {seasonalPresets.map((p) => (
                <button key={p.label} type="button"
                  onClick={() => { setFrom(p.from); setTo(p.to); }}
                  className="text-xs px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700">
                  {p.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Presety "eksperckie" (całe lata) - collapsible */}
        <div className="mt-3 pt-3 border-t border-slate-800">
          <button type="button" onClick={() => setShowExpert(!showExpert)}
            className="text-xs text-slate-500 hover:text-slate-300">
            {showExpert ? '▼' : '▶'} Tryb ekspercki (całe lata)
          </button>
          {showExpert && (
            <div className="flex gap-2 flex-wrap mt-2">
              {expertPresets.map((p) => (
                <button key={p.label} type="button"
                  onClick={() => { setFrom(p.from); setTo(p.to); }}
                  className="text-xs px-2 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700">
                  {p.label}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {rangeMismatch && (
        <div className="text-sm text-yellow-300 bg-yellow-900/30 border border-yellow-800 rounded p-2 mb-3">
          ⚠️ Uwaga: to profil <strong>{season}</strong>, a wybrany zakres zawiera miesiące spoza sezonu.
          Wyniki mogą być niereprezentatywne (np. grzejnik odpalany w upale).
        </div>
      )}

      {error && (
        <div className="text-sm text-red-300 bg-red-900/40 border border-red-800 rounded p-2 mb-3">{error}</div>
      )}

      {!proj && !loading && (
        <p className="text-slate-400 text-sm py-8 text-center">
          Wybierz okres i kliknij "Policz projekcję".
        </p>
      )}

      {proj && proj.daysInPeriod === 0 && (
        <p className="text-slate-400 text-sm py-4">Brak danych zużycia w tym teście.</p>
      )}

      {proj && proj.daysInPeriod > 0 && (
        <CostResultView cost={proj} summaryChartData={summaryChartData} />
      )}
    </div>
  );
}

// ==========================================================================
//  CostResultView (bez zmian)
// ==========================================================================

interface SummaryChartRow {
  tariff: string;
  label: string;
  cost: number;
  code: TariffCode;
}

function CostResultView({
  cost, summaryChartData,
}: { cost: ProjectedCostBreakdown; summaryChartData: SummaryChartRow[] }) {
  const savings = cost.rdnVsG11SavingsPercent;
  const savingsColor = savings > 0 ? 'text-green-400' : savings < 0 ? 'text-red-400' : 'text-slate-300';
  const savingsLabel = savings > 0
    ? `RDN tańsze średnio o ${savings.toFixed(1)}% niż G11 w tym okresie`
    : savings < 0
      ? `RDN droższe średnio o ${Math.abs(savings).toFixed(1)}% niż G11 w tym okresie`
      : 'RDN równe G11';

  const dailyChartData = cost.dailyBreakdown.map((d) => ({
    date: d.date.substring(5),
    G11: d.costG11Pln,
    G12: d.costG12Pln,
    RDN: d.costRdnPln,
  }));

  return (
    <>
      <div className="flex items-center justify-between flex-wrap gap-2 mb-4 text-xs text-slate-400">
        <div>
          Okres: <strong className="text-slate-200">{cost.from} — {cost.to}</strong> ·
          {' '}<strong className="text-slate-200">{cost.daysInPeriod}</strong> dni ·
          {' '}Rok cen: <strong className="text-slate-200">{cost.year}</strong> ·
          {' '}Śr. dzienne: <strong className="text-slate-200">{cost.avgDailyKwh.toFixed(2)} kWh</strong>
        </div>
        {cost.daysWithFullPrices < cost.daysInPeriod && (
          <span className="text-yellow-400 bg-yellow-900/30 border border-yellow-800 rounded px-2 py-1">
            {cost.daysWithFullPrices}/{cost.daysInPeriod} dni z pełnymi cenami RDN
          </span>
        )}
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
        <TariffCard code="G11" totalCost={cost.totalCostG11Pln} isCheapest={cost.cheapestTariff === 'G11'} />
        <TariffCard code="G12" totalCost={cost.totalCostG12Pln} isCheapest={cost.cheapestTariff === 'G12'} />
        <TariffCard code="RDN" totalCost={cost.totalCostRdnPln} isCheapest={cost.cheapestTariff === 'RDN'} />
      </div>

      <div className="bg-slate-950 border border-slate-700 rounded p-3 mb-4">
        <h3 className="text-sm font-medium text-slate-200 mb-2">
          Statystyki dziennego kosztu RDN (analiza ryzyka)
        </h3>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
          <StatBox label="Min (najtaniej)"       value={`${cost.rdnDailyMinPln.toFixed(2)} zł`}    color="text-green-400" />
          <StatBox label="Mediana"               value={`${cost.rdnDailyMedianPln.toFixed(2)} zł`} color="text-slate-200" />
          <StatBox label="VaR 5% (95 percentyl)" value={`${cost.rdnVaR5PercentPln.toFixed(2)} zł`} color="text-orange-400" />
          <StatBox label="Max (worst case)"      value={`${cost.rdnDailyMaxPln.toFixed(2)} zł`}    color="text-red-400" />
        </div>
      </div>

      <div className={`bg-slate-950 border border-slate-700 rounded p-3 mb-4 text-sm ${savingsColor}`}>
        <strong>Wniosek:</strong> {savingsLabel}
      </div>

      <div className="mb-6">
        <h3 className="text-sm font-medium text-slate-200 mb-2">Suma kosztu per taryfa (cały okres)</h3>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={summaryChartData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
            <XAxis dataKey="tariff" stroke="#94a3b8" fontSize={12} />
            <YAxis stroke="#94a3b8" fontSize={11}
              label={{ value: 'zł', angle: -90, position: 'insideLeft', fill: '#94a3b8' }} />
            <Tooltip contentStyle={tooltipStyle}
              formatter={(v: number) => [`${v.toFixed(2)} zł`, 'Koszt']} />
            <Bar dataKey="cost" radius={[4, 4, 0, 0]}>
              {summaryChartData.map((entry, idx) => (
                <Cell key={idx} fill={TARIFF_COLORS[entry.code]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      {dailyChartData.length > 1 && (
        <div>
          <h3 className="text-sm font-medium text-slate-200 mb-2">
            Koszt dzienny w 3 taryfach ({cost.daysInPeriod} dni)
          </h3>
          <ResponsiveContainer width="100%" height={320}>
            <LineChart data={dailyChartData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
              <XAxis dataKey="date" stroke="#94a3b8" fontSize={10}
                interval={Math.max(0, Math.floor(dailyChartData.length / 12))} />
              <YAxis stroke="#94a3b8" fontSize={11}
                label={{ value: 'zł/dzień', angle: -90, position: 'insideLeft', fill: '#94a3b8' }} />
              <Tooltip contentStyle={tooltipStyle}
                formatter={(v: number) => [`${v.toFixed(2)} zł`, '']} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Line dataKey="G11" stroke={TARIFF_COLORS.G11} strokeWidth={1.5} dot={false} />
              <Line dataKey="G12" stroke={TARIFF_COLORS.G12} strokeWidth={1.5} dot={false} />
              <Line dataKey="RDN" stroke={TARIFF_COLORS.RDN} strokeWidth={1.5} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </>
  );
}

// ==========================================================================
//  Helpers
// ==========================================================================

function TariffCard({
  code, totalCost, isCheapest,
}: { code: TariffCode; totalCost: number; isCheapest: boolean }) {
  return (
    <div className={`bg-slate-950 border rounded p-3 relative ${
      isCheapest ? 'border-green-600' : 'border-slate-700'
    }`}>
      {isCheapest && (
        <span className="absolute top-1 right-2 text-xs bg-green-700 text-white px-2 py-0.5 rounded">
          NAJTAŃSZE
        </span>
      )}
      <div className="flex items-center gap-2 mb-1">
        <span className="inline-block w-3 h-3 rounded" style={{ backgroundColor: TARIFF_COLORS[code] }} />
        <span className="text-xs text-slate-400 uppercase tracking-wider">{code}</span>
      </div>
      <div className="text-xs text-slate-500">{TARIFF_LABELS[code]}</div>
      <div className="text-2xl font-semibold text-slate-100 mt-2">
        {totalCost.toFixed(2)} <span className="text-sm text-slate-400">zł</span>
      </div>
    </div>
  );
}

function StatBox({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div className="bg-slate-800 border border-slate-700 rounded p-2">
      <div className="text-xs text-slate-500">{label}</div>
      <div className={`text-sm font-semibold mt-0.5 ${color}`}>{value}</div>
    </div>
  );
}

const tooltipStyle = {
  backgroundColor: '#1e293b',
  border: '1px solid #334155',
  borderRadius: 4,
} as const;

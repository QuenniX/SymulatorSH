import { useMemo, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { fetchPricesFromPse, listPricesByDate } from '../api';
import type { EnergyPrice } from '../types';

/**
 * Strona "Ceny energii" - dashboard z cenami RDN.
 * MVP: wybór dnia, ręczne pobranie z PSE, wykres słupkowy 24h, statystyki.
 */
export default function PricesPage() {
  // Domyślnie wczoraj (bo dziś może być niepełne).
  const yesterday = useMemo(() => {
    const d = new Date();
    d.setDate(d.getDate() - 1);
    return d.toISOString().split('T')[0];
  }, []);

  const [date, setDate] = useState(yesterday);
  const [prices, setPrices] = useState<EnergyPrice[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  /**
   * Smart loading: najpierw sprawdza bazę, jeśli pusto -- pobiera z PSE i pokazuje.
   * Jeden przycisk zamiast dwóch, żeby użytkownik nie musiał myśleć.
   */
  async function handleShow() {
    setLoading(true);
    setError(null);
    setMessage(null);
    setPrices([]);
    try {
      // Krok 1: spróbuj z bazy.
      let data = await listPricesByDate(date);

      if (data.length > 0) {
        setPrices(data);
        setMessage(`Załadowano ${data.length} rekordów z bazy (${date}).`);
        return;
      }

      // Krok 2: baza pusta -- pobierz z PSE.
      setMessage(`Brak w bazie -- pobieram z PSE dla ${date}...`);
      const result = await fetchPricesFromPse(date);

      // Krok 3: załaduj świeżo pobrane dane z bazy.
      data = await listPricesByDate(date);
      setPrices(data);
      setMessage(
        `Pobrano ${result.savedRecords} nowych rekordów z PSE i zapisano do bazy (${date}).`,
      );
    } catch (err: unknown) {
      let msg = err instanceof Error ? err.message : String(err);
      if (err && typeof err === 'object' && 'response' in err) {
        const e = err as { response?: { data?: { message?: string } } };
        msg = e.response?.data?.message ?? msg;
      }
      setError(`Błąd: ${msg}`);
    } finally {
      setLoading(false);
    }
  }

  // Statystyki dobowe
  const stats = useMemo(() => {
    if (prices.length === 0) return null;
    const values = prices.map((p) => p.pricePlnMwh);
    const avg = values.reduce((s, v) => s + v, 0) / values.length;
    const min = Math.min(...values);
    const max = Math.max(...values);
    const minHour = prices.find((p) => p.pricePlnMwh === min)?.hour ?? 0;
    const maxHour = prices.find((p) => p.pricePlnMwh === max)?.hour ?? 0;
    return { avg, min, max, minHour, maxHour };
  }, [prices]);

  // Kolorystyka słupków: zielony (poniżej średniej), żółty (blisko), czerwony (powyżej)
  function colorForPrice(p: EnergyPrice, avg: number): string {
    const ratio = p.pricePlnMwh / avg;
    if (ratio < 0.75) return '#22c55e'; // zielony -- tanio
    if (ratio > 1.25) return '#ef4444'; // czerwony -- drogo
    return '#f59e0b'; // żółty -- średnio
  }

  const chartData = prices.map((p) => ({
    hour: `${p.hour.toString().padStart(2, '0')}:00`,
    price: p.pricePlnMwh,
  }));

  return (
    <div>
      <h1 className="text-2xl font-bold text-slate-100 mb-2">Ceny energii (RDN)</h1>
      <p className="text-sm text-slate-400 mb-6">
        Godzinowe ceny z Rynku Dnia Następnego - dane z API PSE.
      </p>

      {/* Panel wyboru dnia + przyciski */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-6">
        <div className="flex items-end gap-3 flex-wrap">
          <label className="text-xs text-slate-400">
            Data
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="mt-1 block px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
            />
          </label>
          <button
            type="button"
            onClick={handleShow}
            disabled={loading}
            className="px-4 py-2 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 text-white rounded text-sm font-medium"
          >
            {loading ? 'Ładowanie...' : 'Wyświetl'}
          </button>
        </div>

        {message && (
          <div className="mt-3 text-sm text-green-300 bg-green-900/30 border border-green-800 rounded p-2">
            {message}
          </div>
        )}
        {error && (
          <div className="mt-3 text-sm text-red-300 bg-red-900/40 border border-red-800 rounded p-2">
            {error}
          </div>
        )}
      </div>

      {/* Statystyki */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          <div className="bg-slate-800 border border-slate-700 rounded p-4">
            <div className="text-xs text-slate-400 uppercase">Średnia</div>
            <div className="text-xl font-semibold text-slate-100 mt-1">
              {stats.avg.toFixed(0)} <span className="text-sm text-slate-400">zł/MWh</span>
            </div>
          </div>
          <div className="bg-slate-800 border border-slate-700 rounded p-4">
            <div className="text-xs text-slate-400 uppercase">Najniższa (dolina)</div>
            <div className="text-xl font-semibold text-green-400 mt-1">
              {stats.min.toFixed(0)} <span className="text-sm text-slate-400">zł/MWh</span>
            </div>
            <div className="text-xs text-slate-500">o {stats.minHour.toString().padStart(2, '0')}:00</div>
          </div>
          <div className="bg-slate-800 border border-slate-700 rounded p-4">
            <div className="text-xs text-slate-400 uppercase">Najwyższa (szczyt)</div>
            <div className="text-xl font-semibold text-red-400 mt-1">
              {stats.max.toFixed(0)} <span className="text-sm text-slate-400">zł/MWh</span>
            </div>
            <div className="text-xs text-slate-500">o {stats.maxHour.toString().padStart(2, '0')}:00</div>
          </div>
          <div className="bg-slate-800 border border-slate-700 rounded p-4">
            <div className="text-xs text-slate-400 uppercase">Stosunek max/min</div>
            <div className="text-xl font-semibold text-slate-100 mt-1">
              ×{(stats.max / stats.min).toFixed(1)}
            </div>
          </div>
        </div>
      )}

      {/* Wykres słupkowy 24h */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4">
        <h2 className="text-lg font-semibold text-slate-100 mb-3">
          Godzinowy profil cen ({prices.length} rekordów)
        </h2>
        {prices.length === 0 ? (
          <p className="text-slate-400 py-12 text-center">
            Wybierz datę i kliknij "Wyświetl" -- jeśli brak w bazie, automatycznie pobierze z PSE.
          </p>
        ) : (
          <ResponsiveContainer width="100%" height={400}>
            <BarChart data={chartData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
              <XAxis dataKey="hour" stroke="#94a3b8" fontSize={11} />
              <YAxis
                stroke="#94a3b8"
                fontSize={11}
                label={{ value: 'zł/MWh', angle: -90, position: 'insideLeft', fill: '#94a3b8' }}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: '#1e293b',
                  border: '1px solid #334155',
                  borderRadius: 4,
                }}
                formatter={(v: number) => [`${v.toFixed(2)} zł/MWh`, 'Cena']}
              />
              <Bar dataKey="price" radius={[3, 3, 0, 0]}>
                {chartData.map((_, idx) => (
                  <Cell
                    key={idx}
                    fill={stats ? colorForPrice(prices[idx], stats.avg) : '#3b82f6'}
                  />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* Info kolorystyki */}
      {prices.length > 0 && (
        <div className="mt-3 flex gap-4 text-xs text-slate-400">
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-3 bg-green-500 rounded"></span> Tanio (poniżej -25% średniej)
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-3 bg-yellow-500 rounded"></span> Średnio
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-3 bg-red-500 rounded"></span> Drogo (powyżej +25% średniej)
          </span>
        </div>
      )}
    </div>
  );
}

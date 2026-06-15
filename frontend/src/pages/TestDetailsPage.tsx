import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { deleteTest, getMeasurements, getTest } from '../api';
import type { MeasurementPoint, TestResponse } from '../types';
import StatusBadge from '../components/StatusBadge';

// Paleta kolorów dla linii na wykresie (cykliczna)
const COLORS = [
  '#3b82f6', // blue
  '#10b981', // emerald
  '#f59e0b', // amber
  '#ec4899', // pink
  '#8b5cf6', // violet
  '#14b8a6', // teal
];

interface ChartRow {
  timestamp: number;
  [deviceId: string]: number;
}

function aggregateForChart(points: MeasurementPoint[]): {
  rows: ChartRow[];
  devices: string[];
} {
  const deviceSet = new Set<string>();
  const byTime = new Map<number, ChartRow>();

  for (const p of points) {
    const ts = new Date(p.timestamp).getTime();
    deviceSet.add(p.deviceId);
    if (!byTime.has(ts)) {
      byTime.set(ts, { timestamp: ts });
    }
    byTime.get(ts)![p.deviceId] = p.powerW;
  }

  const rows = Array.from(byTime.values()).sort((a, b) => a.timestamp - b.timestamp);
  return { rows, devices: Array.from(deviceSet).sort() };
}

function formatChartTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pl-PL', { dateStyle: 'short', timeStyle: 'medium' });
}

export default function TestDetailsPage() {
  const { id } = useParams<{ id: string }>();
  const [test, setTest] = useState<TestResponse | null>(null);
  const [points, setPoints] = useState<MeasurementPoint[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  async function refresh() {
    if (!id) return;
    try {
      const [testData, measurements] = await Promise.all([
        getTest(id),
        getMeasurements(id),
      ]);
      setTest(testData);
      setPoints(measurements.points);
      setError(null);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
    }
  }

  useEffect(() => {
    refresh();
    const interval = setInterval(() => {
      if (test?.status === 'COMPLETED' || test?.status === 'FAILED' || test?.status === 'CANCELLED') {
        // test zakończony - przestań odpytywać
        return;
      }
      refresh();
    }, 3000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, test?.status]);

  async function handleDelete() {
    if (!id) return;
    if (!confirm('Na pewno chcesz anulować / usunąć ten test?')) return;
    setDeleting(true);
    try {
      await deleteTest(id);
      window.location.href = '/';
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Błąd usuwania: ${msg}`);
      setDeleting(false);
    }
  }

  if (!test) {
    return (
      <div>
        <Link to="/" className="text-brand-500 hover:underline">← Powrót do listy</Link>
        <p className="text-slate-400 mt-4">Wczytywanie...</p>
        {error && <p className="text-red-400 mt-2">{error}</p>}
      </div>
    );
  }

  const { rows, devices } = aggregateForChart(points);
  const totalKwh = points.reduce((s, p) => s + p.powerW, 0) / 60 / 1000; // krok = 1 minuta sym

  return (
    <div>
      <Link to="/" className="text-brand-500 hover:underline text-sm">← Powrót do listy</Link>

      <div className="flex items-start justify-between mt-2 mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-100">{test.name}</h1>
          {test.description && (
            <p className="text-slate-400 mt-1">{test.description}</p>
          )}
          <p className="text-xs text-slate-500 mt-2 font-mono">{test.testId}</p>
        </div>
        <button
          onClick={handleDelete}
          disabled={deleting}
          className="px-3 py-1.5 bg-red-700 hover:bg-red-800 disabled:bg-slate-600 text-white rounded text-sm font-medium transition"
        >
          {deleting ? 'Usuwanie...' : 'Anuluj / Usuń'}
        </button>
      </div>

      {error && (
        <div className="bg-red-900/50 border border-red-700 text-red-200 p-3 rounded mb-4 text-sm">
          {error}
        </div>
      )}

      {/* Karty informacyjne */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        <div className="bg-slate-800 border border-slate-700 rounded p-4">
          <div className="text-xs text-slate-400 uppercase tracking-wider">Status</div>
          <div className="mt-1"><StatusBadge status={test.status} /></div>
        </div>
        <div className="bg-slate-800 border border-slate-700 rounded p-4">
          <div className="text-xs text-slate-400 uppercase tracking-wider">Dni symulacji</div>
          <div className="text-xl font-semibold text-slate-100 mt-1">{test.durationDays}</div>
        </div>
        <div className="bg-slate-800 border border-slate-700 rounded p-4">
          <div className="text-xs text-slate-400 uppercase tracking-wider">Speed factor</div>
          <div className="text-xl font-semibold text-slate-100 mt-1">×{test.speedFactor}</div>
        </div>
        <div className="bg-slate-800 border border-slate-700 rounded p-4">
          <div className="text-xs text-slate-400 uppercase tracking-wider">Zużycie</div>
          <div className="text-xl font-semibold text-slate-100 mt-1">
            {totalKwh.toFixed(2)} <span className="text-sm text-slate-400">kWh</span>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4 mb-6 text-sm">
        <div className="bg-slate-800 border border-slate-700 rounded p-3">
          <span className="text-slate-400">Utworzony:</span>{' '}
          <span className="text-slate-200">{formatDateTime(test.createdAt)}</span>
        </div>
        <div className="bg-slate-800 border border-slate-700 rounded p-3">
          <span className="text-slate-400">Zakończony:</span>{' '}
          <span className="text-slate-200">{formatDateTime(test.finishedAt)}</span>
        </div>
        {test.realDurationSeconds !== null && (
          <div className="bg-slate-800 border border-slate-700 rounded p-3 col-span-2">
            <span className="text-slate-400">Czas rzeczywisty wykonania:</span>{' '}
            <span className="text-slate-200">{test.realDurationSeconds} s</span>
          </div>
        )}
      </div>

      {test.errorMessage && (
        <div className="bg-red-900/50 border border-red-700 text-red-200 p-4 rounded mb-6">
          <strong>Błąd:</strong> {test.errorMessage}
        </div>
      )}

      {/* Wykres mocy */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-6">
        <h2 className="text-lg font-semibold text-slate-100 mb-3">
          Pobór mocy w czasie ({points.length} pomiarów)
        </h2>
        {rows.length === 0 ? (
          <p className="text-slate-400 py-12 text-center">
            Brak pomiarów. Jeśli test jest w trakcie, dane pojawią się tu na żywo.
          </p>
        ) : (
          <ResponsiveContainer width="100%" height={400}>
            <LineChart data={rows} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
              <XAxis
                dataKey="timestamp"
                tickFormatter={formatChartTime}
                stroke="#94a3b8"
                fontSize={11}
              />
              <YAxis
                stroke="#94a3b8"
                fontSize={11}
                label={{ value: 'W', angle: -90, position: 'insideLeft', fill: '#94a3b8' }}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: '#1e293b',
                  border: '1px solid #334155',
                  borderRadius: 4,
                }}
                labelFormatter={(ts) => new Date(ts).toLocaleString('pl-PL')}
                formatter={(v: number) => [`${v.toFixed(0)} W`, '']}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              {devices.map((dev, idx) => (
                <Line
                  key={dev}
                  type="monotone"
                  dataKey={dev}
                  stroke={COLORS[idx % COLORS.length]}
                  strokeWidth={1.5}
                  dot={false}
                  connectNulls
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* JSON konfiguracji */}
      <details className="bg-slate-800 border border-slate-700 rounded p-4 mb-6">
        <summary className="cursor-pointer font-medium text-slate-200">
          Konfiguracja JSON
        </summary>
        <pre className="mt-3 p-3 bg-slate-950 border border-slate-700 rounded text-xs font-mono text-slate-300 overflow-x-auto">
          {JSON.stringify(test.config, null, 2)}
        </pre>
      </details>
    </div>
  );
}

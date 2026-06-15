import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listTests } from '../api';
import type { TestSummary } from '../types';
import StatusBadge from '../components/StatusBadge';

function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('pl-PL', { dateStyle: 'short', timeStyle: 'medium' });
}

export default function TestListPage() {
  const [tests, setTests] = useState<TestSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    try {
      const data = await listTests();
      setTests(data);
      setError(null);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Błąd pobierania listy: ${msg}`);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    const interval = setInterval(refresh, 5000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-100">Lista testów</h1>
          <p className="text-sm text-slate-400 mt-1">
            Odświeżanie automatyczne co 5 sekund
          </p>
        </div>
        <Link
          to="/new"
          className="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white rounded font-medium transition"
        >
          + Nowy test
        </Link>
      </div>

      {error && (
        <div className="bg-red-900/50 border border-red-700 text-red-200 p-4 rounded mb-4">
          {error}
        </div>
      )}

      {loading ? (
        <p className="text-slate-400">Wczytywanie...</p>
      ) : tests.length === 0 ? (
        <div className="bg-slate-800 border border-slate-700 rounded p-12 text-center">
          <p className="text-slate-400 mb-4">Brak testów. Zacznij od utworzenia pierwszego.</p>
          <Link
            to="/new"
            className="inline-block px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white rounded font-medium transition"
          >
            + Nowy test
          </Link>
        </div>
      ) : (
        <div className="bg-slate-800 border border-slate-700 rounded overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-slate-900/50 text-slate-300 uppercase text-xs tracking-wider">
              <tr>
                <th className="px-4 py-3 text-left">Nazwa</th>
                <th className="px-4 py-3 text-left">Status</th>
                <th className="px-4 py-3 text-left">Dni sym.</th>
                <th className="px-4 py-3 text-left">Speed</th>
                <th className="px-4 py-3 text-left">Utworzony</th>
                <th className="px-4 py-3 text-left">Zakończony</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-700">
              {tests.map((t) => (
                <tr key={t.testId} className="hover:bg-slate-700/30 transition">
                  <td className="px-4 py-3">
                    <Link
                      to={`/tests/${t.testId}`}
                      className="text-brand-500 hover:text-brand-400 font-medium"
                    >
                      {t.name}
                    </Link>
                    <div className="text-xs text-slate-500 mt-0.5 font-mono">
                      {t.testId.slice(0, 8)}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={t.status} />
                  </td>
                  <td className="px-4 py-3 text-slate-300">{t.durationDays}</td>
                  <td className="px-4 py-3 text-slate-300">×{t.speedFactor}</td>
                  <td className="px-4 py-3 text-slate-400 text-xs">
                    {formatDateTime(t.createdAt)}
                  </td>
                  <td className="px-4 py-3 text-slate-400 text-xs">
                    {formatDateTime(t.finishedAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

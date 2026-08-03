import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { listTests } from '../api';
import type { TestStatus, TestSummary } from '../types';
import StatusBadge from '../components/StatusBadge';

function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('pl-PL', { dateStyle: 'short', timeStyle: 'medium' });
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('pl-PL', { day: '2-digit', month: 'long', year: 'numeric' });
}

/**
 * Wyodrębnia klucz grupujący z nazwy testu:
 * - jeśli nazwa zaczyna się od prefiksu w [...] (np. "[BATCH 2026-08-15]"), używa tego prefiksu
 * - inaczej fallback do daty utworzenia (grupowanie po dniach)
 */
function groupKeyForTest(t: TestSummary): { key: string; label: string; sortValue: string } {
  const prefixMatch = t.name.match(/^\[([^\]]+)\]/);
  if (prefixMatch) {
    const prefix = prefixMatch[1].trim();
    return { key: `prefix:${prefix}`, label: prefix, sortValue: prefix };
  }
  // Fallback - grupuj po dacie utworzenia (yyyy-mm-dd)
  const date = t.createdAt ? t.createdAt.split('T')[0] : 'brak_daty';
  return {
    key: `date:${date}`,
    label: formatDate(t.createdAt),
    sortValue: date,
  };
}

interface TestGroup {
  key: string;
  label: string;
  tests: TestSummary[];
  counts: Record<TestStatus, number>;
}

export default function TestListPage() {
  const [tests, setTests] = useState<TestSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Zapamietane jakie grupy sa zwinięte (przy odswiezaniu listy stan się zachowuje)
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

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

  // Grupowanie testow. Grupy posortowane od najnowszej.
  const groups: TestGroup[] = useMemo(() => {
    const byKey = new Map<string, TestGroup>();
    for (const t of tests) {
      const { key, label } = groupKeyForTest(t);
      if (!byKey.has(key)) {
        byKey.set(key, {
          key,
          label,
          tests: [],
          counts: { QUEUED: 0, RUNNING: 0, COMPLETED: 0, FAILED: 0, CANCELLED: 0 },
        });
      }
      const g = byKey.get(key)!;
      g.tests.push(t);
      g.counts[t.status] = (g.counts[t.status] ?? 0) + 1;
    }
    // Sortuj testy wewnatrz grupy po dacie (najnowsze pierwsze)
    for (const g of byKey.values()) {
      g.tests.sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
    }
    // Sortuj grupy - te z najnowszymi testami pierwsze
    return Array.from(byKey.values()).sort((a, b) => {
      const aLatest = a.tests[0]?.createdAt ?? '';
      const bLatest = b.tests[0]?.createdAt ?? '';
      return bLatest.localeCompare(aLatest);
    });
  }, [tests]);

  function toggleCollapse(key: string) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-100">Lista testów</h1>
          <p className="text-sm text-slate-400 mt-1">
            {tests.length} {tests.length === 1 ? 'test' : 'testów'} w {groups.length} {groups.length === 1 ? 'grupie' : 'grupach'} · Odświeżanie co 5 sekund
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
        <div className="space-y-4">
          {groups.map((g) => {
            const isCollapsed = collapsed.has(g.key);
            return (
              <div key={g.key} className="bg-slate-800 border border-slate-700 rounded overflow-hidden">
                {/* Naglowek grupy - klikalny do zwijania */}
                <button
                  type="button"
                  onClick={() => toggleCollapse(g.key)}
                  className="w-full bg-slate-900/50 hover:bg-slate-900 px-4 py-3 flex items-center justify-between gap-4 text-left transition"
                >
                  <div className="flex items-center gap-3">
                    <span className="text-slate-400 text-xs">{isCollapsed ? '▶' : '▼'}</span>
                    <h2 className="text-sm font-semibold text-slate-100">{g.label}</h2>
                    <span className="text-xs text-slate-500">
                      ({g.tests.length} {g.tests.length === 1 ? 'test' : 'testów'})
                    </span>
                  </div>
                  <div className="flex items-center gap-2 flex-wrap">
                    {(['RUNNING', 'QUEUED', 'COMPLETED', 'FAILED', 'CANCELLED'] as TestStatus[])
                      .filter((s) => g.counts[s] > 0)
                      .map((s) => (
                        <span key={s} className="text-xs">
                          <StatusBadge status={s} /> <span className="text-slate-400">×{g.counts[s]}</span>
                        </span>
                      ))}
                  </div>
                </button>

                {/* Tabela testow grupy (zwijalna) */}
                {!isCollapsed && (
                  <table className="w-full text-sm">
                    <thead className="bg-slate-900/30 text-slate-300 uppercase text-xs tracking-wider">
                      <tr>
                        <th className="px-4 py-2 text-left">Nazwa</th>
                        <th className="px-4 py-2 text-left">Status</th>
                        <th className="px-4 py-2 text-left">Dni sym.</th>
                        <th className="px-4 py-2 text-left">Speed</th>
                        <th className="px-4 py-2 text-left">Utworzony</th>
                        <th className="px-4 py-2 text-left">Zakończony</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-700">
                      {g.tests.map((t) => (
                        <tr key={t.testId} className="hover:bg-slate-700/30 transition">
                          <td className="px-4 py-2">
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
                          <td className="px-4 py-2">
                            <StatusBadge status={t.status} />
                          </td>
                          <td className="px-4 py-2 text-slate-300">{t.durationDays}</td>
                          <td className="px-4 py-2 text-slate-300">×{t.speedFactor}</td>
                          <td className="px-4 py-2 text-slate-400 text-xs">
                            {formatDateTime(t.createdAt)}
                          </td>
                          <td className="px-4 py-2 text-slate-400 text-xs">
                            {formatDateTime(t.finishedAt)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

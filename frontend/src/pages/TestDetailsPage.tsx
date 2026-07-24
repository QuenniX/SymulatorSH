import { Suspense, useEffect, useMemo, useRef, useState } from 'react';
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
import { Canvas } from '@react-three/fiber';
import { ContactShadows, Environment, OrbitControls, Sky } from '@react-three/drei';
import { deleteTest, getMeasurements, getTest, listRooms } from '../api';
import type { MeasurementPoint, Room, TestResponse } from '../types';
import StatusBadge from '../components/StatusBadge';
import Apartment from '../three/Apartment';

// Paleta kolorów dla linii na wykresie (cykliczna)
const COLORS = [
  '#3b82f6', '#10b981', '#f59e0b', '#ec4899',
  '#8b5cf6', '#14b8a6', '#ef4444', '#a3e635',
];

type TimeMode = 'sim' | 'real';
type ChartMode = 'combined' | 'separate';
type ScaleMode = 'linear' | 'log';
type ViewMode = 'chart' | '3d';

interface ChartRow {
  timeKey: number;
  [deviceId: string]: number;
}

function aggregateForChart(
  points: MeasurementPoint[],
  startedAt: string | null,
  speedFactor: number,
  timeMode: TimeMode,
): { rows: ChartRow[]; devices: string[] } {
  const deviceSet = new Set<string>();
  const byTime = new Map<number, ChartRow>();
  const startMs = startedAt ? new Date(startedAt).getTime() : 0;

  for (const p of points) {
    const realMs = new Date(p.timestamp).getTime();
    let key: number;
    if (timeMode === 'sim' && startedAt) {
      const elapsedMs = realMs - startMs;
      const simMinutes = (elapsedMs * speedFactor) / 60000;
      key = Math.round(simMinutes);
    } else {
      key = realMs;
    }
    deviceSet.add(p.deviceId);
    if (!byTime.has(key)) {
      byTime.set(key, { timeKey: key });
    }
    byTime.get(key)![p.deviceId] = p.powerW;
  }
  const rows = Array.from(byTime.values()).sort((a, b) => a.timeKey - b.timeKey);
  return { rows, devices: Array.from(deviceSet).sort() };
}

function formatSimTime(minutesTotal: number): string {
  const day = Math.floor(minutesTotal / 1440) + 1;
  const minuteOfDay = ((minutesTotal % 1440) + 1440) % 1440;
  const hh = Math.floor(minuteOfDay / 60).toString().padStart(2, '0');
  const mm = Math.floor(minuteOfDay % 60).toString().padStart(2, '0');
  if (day > 1) return `D${day} ${hh}:${mm}`;
  return `${hh}:${mm}`;
}

function formatRealTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('pl-PL', { dateStyle: 'short', timeStyle: 'medium' });
}

/** Nominalna moc urządzenia do skalowania aktywności (0-1). */
function nominalPowerFor(deviceId: string, config: unknown): number {
  const cfg = config as { devices?: Array<{ id: string; type: string; params?: Record<string, number> }> } | null;
  const dev = cfg?.devices?.find((d) => d.id === deviceId);
  if (!dev) return 100;
  const params = dev.params ?? {};
  const p =
    params.power_w ??
    params.burst_power_w ??
    params.heat_power_w ??
    (params.idle_power_w ? params.idle_power_w * 2 : undefined);
  if (typeof p === 'number' && p > 0) return p;
  // Domyślne nominalne moce
  const defaults: Record<string, number> = {
    LIGHT: 60, TV: 120, HEATER: 1500, REFRIGERATOR: 150,
    WASHER: 2000, KETTLE: 2000, OVEN: 2500, DISHWASHER: 1800,
    AC: 1000, BOILER: 2000, COMPUTER: 200, ROUTER: 15,
  };
  return defaults[dev.type] ?? 200;
}

export default function TestDetailsPage() {
  const { id } = useParams<{ id: string }>();
  const [test, setTest] = useState<TestResponse | null>(null);
  const [points, setPoints] = useState<MeasurementPoint[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Ustawienia widoku
  const [viewMode, setViewMode] = useState<ViewMode>('chart');
  const [timeMode, setTimeMode] = useState<TimeMode>('sim');
  const [chartMode, setChartMode] = useState<ChartMode>('combined');
  const [scaleMode, setScaleMode] = useState<ScaleMode>('linear');

  // Timeline scrubber dla widoku 3D
  const [scrubberEnabled, setScrubberEnabled] = useState(false);
  const [scrubberMinute, setScrubberMinute] = useState(0);
  const [playing, setPlaying] = useState(false);

  const statusRef = useRef<string | null>(null);
  useEffect(() => {
    statusRef.current = test?.status ?? null;
  }, [test?.status]);

  async function refresh() {
    if (!id) return;
    try {
      const [testData, measurements] = await Promise.all([getTest(id), getMeasurements(id)]);
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
      const s = statusRef.current;
      if (s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED') return;
      refresh();
    }, 1000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Pobierz listę pokoi (potrzebna dla 3D)
  useEffect(() => {
    listRooms().then(setRooms).catch(() => setRooms([]));
  }, []);

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

  const effectiveStart = test?.startedAt ?? test?.createdAt ?? null;

  const { rows, devices } = useMemo(
    () => aggregateForChart(points, effectiveStart, test?.speedFactor ?? 1, timeMode),
    [points, effectiveStart, test?.speedFactor, timeMode],
  );

  const totalKwh = points.reduce((s, p) => s + p.powerW, 0) / 60 / 1000;

  // Wyliczenie mapy urządzeń do pokoi (potrzebne w 3D)
  const devicesFor3D = useMemo(() => {
    const cfg = test?.config as { devices?: Array<{ id: string; type: string; room?: string }> } | null;
    return cfg?.devices ?? [];
  }, [test?.config]);

  // Maks minuta z pomiarów (dla timeline scrubber)
  const maxSimMinute = useMemo(() => {
    if (rows.length === 0 || timeMode !== 'sim') return 0;
    return rows[rows.length - 1].timeKey;
  }, [rows, timeMode]);

  // Automatyczny scrubber: gdy playing - przesuwaj
  useEffect(() => {
    if (!playing || !scrubberEnabled) return;
    const interval = setInterval(() => {
      setScrubberMinute((m) => {
        if (m >= maxSimMinute) {
          setPlaying(false);
          return m;
        }
        return m + 5;
      });
    }, 50);
    return () => clearInterval(interval);
  }, [playing, scrubberEnabled, maxSimMinute]);

  /**
   * Wyliczenie aktywności każdego urządzenia (0-1) w danej chwili.
   * Dla trybu live (bez scrubbera) - używa najnowszego pomiaru.
   * Dla scrubbera - używa mocy w okolicy scrubberMinute.
   */
  const activityByDeviceId = useMemo(() => {
    const map = new Map<string, number>();
    if (rows.length === 0) return map;

    let referenceRow: ChartRow;
    if (scrubberEnabled) {
      // Znajdź row najbliższy scrubberMinute (używamy binary search)
      let bestIdx = 0;
      let bestDiff = Infinity;
      for (let i = 0; i < rows.length; i++) {
        const diff = Math.abs(rows[i].timeKey - scrubberMinute);
        if (diff < bestDiff) {
          bestDiff = diff;
          bestIdx = i;
        }
      }
      referenceRow = rows[bestIdx];
    } else {
      // Live: najnowszy row
      referenceRow = rows[rows.length - 1];
    }

    for (const dev of devices) {
      const power = referenceRow[dev];
      if (typeof power !== 'number' || power <= 0) {
        map.set(dev, 0);
        continue;
      }
      const nominal = nominalPowerFor(dev, test?.config);
      map.set(dev, Math.min(1, power / nominal));
    }
    return map;
  }, [rows, devices, scrubberEnabled, scrubberMinute, test?.config]);

  if (!test) {
    return (
      <div>
        <Link to="/" className="text-brand-500 hover:underline">← Powrót do listy</Link>
        <p className="text-slate-400 mt-4">Wczytywanie...</p>
        {error && <p className="text-red-400 mt-2">{error}</p>}
      </div>
    );
  }

  const formatTick = (val: number) => (timeMode === 'sim' ? formatSimTime(val) : formatRealTime(val));
  const formatTooltipLabel = (val: number) => {
    if (timeMode === 'sim') return `Symulowany: ${formatSimTime(val)}`;
    return new Date(val).toLocaleString('pl-PL');
  };

  return (
    <div>
      <Link to="/" className="text-brand-500 hover:underline text-sm">← Powrót do listy</Link>

      <div className="flex items-start justify-between mt-2 mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-100">{test.name}</h1>
          {test.description && <p className="text-slate-400 mt-1">{test.description}</p>}
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

      {/* Widok danych - toggle Wykres/3D */}
      <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-6">
        <div className="flex items-center justify-between mb-4 flex-wrap gap-2">
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold text-slate-100">
              {viewMode === 'chart' ? 'Pobór mocy w czasie' : 'Wizualizacja 3D mieszkania'}
            </h2>
            <span className="text-xs text-slate-500">({points.length} pomiarów)</span>
          </div>

          <div className="flex gap-2 flex-wrap">
            <ToggleGroup
              label=""
              value={viewMode}
              options={[
                { value: 'chart', label: '📊 Wykres' },
                { value: '3d', label: '🏠 Wizualizacja 3D' },
              ]}
              onChange={(v) => setViewMode(v as ViewMode)}
            />
          </div>
        </div>

        {viewMode === 'chart' ? (
          <ChartView
            rows={rows}
            devices={devices}
            chartMode={chartMode}
            scaleMode={scaleMode}
            timeMode={timeMode}
            setChartMode={setChartMode}
            setScaleMode={setScaleMode}
            setTimeMode={setTimeMode}
            formatTick={formatTick}
            formatTooltipLabel={formatTooltipLabel}
          />
        ) : (
          <View3D
            devices={devicesFor3D}
            rooms={rooms}
            activityByDeviceId={activityByDeviceId}
            scrubberEnabled={scrubberEnabled}
            setScrubberEnabled={setScrubberEnabled}
            scrubberMinute={scrubberMinute}
            setScrubberMinute={setScrubberMinute}
            maxSimMinute={maxSimMinute}
            playing={playing}
            setPlaying={setPlaying}
            testStatus={test.status}
          />
        )}
      </div>

      <details className="bg-slate-800 border border-slate-700 rounded p-4 mb-6">
        <summary className="cursor-pointer font-medium text-slate-200">Konfiguracja JSON</summary>
        <pre className="mt-3 p-3 bg-slate-950 border border-slate-700 rounded text-xs font-mono text-slate-300 overflow-x-auto">
          {JSON.stringify(test.config, null, 2)}
        </pre>
      </details>
    </div>
  );
}

// ---- Widok wykresu (jak dotąd) --------------------------------------------

interface ChartViewProps {
  rows: ChartRow[];
  devices: string[];
  chartMode: ChartMode;
  scaleMode: ScaleMode;
  timeMode: TimeMode;
  setChartMode: (v: ChartMode) => void;
  setScaleMode: (v: ScaleMode) => void;
  setTimeMode: (v: TimeMode) => void;
  formatTick: (v: number) => string;
  formatTooltipLabel: (v: number) => string;
}

function ChartView({
  rows, devices, chartMode, scaleMode, timeMode,
  setChartMode, setScaleMode, setTimeMode, formatTick, formatTooltipLabel,
}: ChartViewProps) {
  return (
    <div>
      <div className="flex gap-2 flex-wrap mb-4">
        <ToggleGroup
          label="Widok"
          value={chartMode}
          options={[{ value: 'combined', label: 'Razem' }, { value: 'separate', label: 'Osobno' }]}
          onChange={(v) => setChartMode(v as ChartMode)}
        />
        <ToggleGroup
          label="Skala"
          value={scaleMode}
          options={[{ value: 'linear', label: 'Liniowa' }, { value: 'log', label: 'Log' }]}
          onChange={(v) => setScaleMode(v as ScaleMode)}
          disabled={chartMode === 'separate'}
        />
        <ToggleGroup
          label="Czas"
          value={timeMode}
          options={[{ value: 'sim', label: 'Symulowany' }, { value: 'real', label: 'Realny' }]}
          onChange={(v) => setTimeMode(v as TimeMode)}
        />
      </div>

      {rows.length === 0 ? (
        <p className="text-slate-400 py-12 text-center">
          Brak pomiarów. Jeśli test jest w trakcie, dane pojawią się tu na żywo.
        </p>
      ) : chartMode === 'combined' ? (
        <ResponsiveContainer width="100%" height={400}>
          <LineChart data={rows} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
            <XAxis dataKey="timeKey" tickFormatter={formatTick} stroke="#94a3b8" fontSize={11} type="number" domain={['dataMin', 'dataMax']} />
            <YAxis stroke="#94a3b8" fontSize={11}
              label={{ value: 'W', angle: -90, position: 'insideLeft', fill: '#94a3b8' }}
              scale={scaleMode === 'log' ? 'log' : 'linear'}
              domain={scaleMode === 'log' ? [1, 'auto'] : ['auto', 'auto']}
              allowDataOverflow={scaleMode === 'log'} />
            <Tooltip contentStyle={{ backgroundColor: '#1e293b', border: '1px solid #334155', borderRadius: 4 }}
              labelFormatter={formatTooltipLabel} formatter={(v: number) => [`${v.toFixed(0)} W`, '']} />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            {devices.map((dev, idx) => (
              <Line key={dev} type="monotone" dataKey={dev} stroke={COLORS[idx % COLORS.length]} strokeWidth={1.5} dot={false} connectNulls />
            ))}
          </LineChart>
        </ResponsiveContainer>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {devices.map((dev, idx) => (
            <div key={dev} className="bg-slate-950 border border-slate-700 rounded p-3">
              <div className="flex items-center gap-2 mb-2">
                <div className="w-3 h-3 rounded-full" style={{ backgroundColor: COLORS[idx % COLORS.length] }} />
                <span className="text-sm text-slate-200">{dev}</span>
              </div>
              <ResponsiveContainer width="100%" height={180}>
                <LineChart data={rows} margin={{ top: 5, right: 15, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                  <XAxis dataKey="timeKey" tickFormatter={formatTick} stroke="#94a3b8" fontSize={10} type="number" domain={['dataMin', 'dataMax']} />
                  <YAxis stroke="#94a3b8" fontSize={10}
                    label={{ value: 'W', angle: -90, position: 'insideLeft', fill: '#94a3b8', fontSize: 10 }} />
                  <Tooltip contentStyle={{ backgroundColor: '#1e293b', border: '1px solid #334155', borderRadius: 4, fontSize: 12 }}
                    labelFormatter={formatTooltipLabel} formatter={(v: number) => [`${v.toFixed(0)} W`, dev]} />
                  <Line type="monotone" dataKey={dev} stroke={COLORS[idx % COLORS.length]} strokeWidth={1.5} dot={false} connectNulls />
                </LineChart>
              </ResponsiveContainer>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ---- Widok 3D z timeline scrubberem ---------------------------------------

interface View3DProps {
  devices: Array<{ id: string; type: string; room?: string }>;
  rooms: Room[];
  activityByDeviceId: Map<string, number>;
  scrubberEnabled: boolean;
  setScrubberEnabled: (v: boolean) => void;
  scrubberMinute: number;
  setScrubberMinute: (v: number) => void;
  maxSimMinute: number;
  playing: boolean;
  setPlaying: (v: boolean) => void;
  testStatus: string;
}

function View3D({
  devices, rooms, activityByDeviceId,
  scrubberEnabled, setScrubberEnabled, scrubberMinute, setScrubberMinute,
  maxSimMinute, playing, setPlaying, testStatus,
}: View3DProps) {
  const isComplete = testStatus === 'COMPLETED' || testStatus === 'FAILED' || testStatus === 'CANCELLED';
  const activeCount = Array.from(activityByDeviceId.values()).filter((v) => v > 0.05).length;

  return (
    <div>
      <div className="flex items-center gap-4 mb-3 text-xs text-slate-400 flex-wrap">
        <span>
          {devices.length} urządzeń w {new Set(devices.map((d) => d.room).filter(Boolean)).size} pomieszczeniach
        </span>
        <span>•</span>
        <span className="text-yellow-300">{activeCount} aktywnych teraz</span>
        <span>•</span>
        <span className="text-slate-500">
          {scrubberEnabled ? `Przewinięto: ${formatSimTime(scrubberMinute)}` : (testStatus === 'RUNNING' ? '🔴 Live (najnowszy stan)' : 'Stan końcowy')}
        </span>
      </div>

      <div className="bg-slate-900 border border-slate-700 rounded overflow-hidden" style={{ height: '55vh', minHeight: 400 }}>
        <Canvas shadows camera={{ position: [7, 8, 9], fov: 45 }} gl={{ antialias: true, toneMappingExposure: 1.1 }}>
          <Suspense fallback={null}>
            {/* Realistyczne niebo - słoneczne popołudnie */}
            <Sky distance={450000} sunPosition={[10, 6, 5]} inclination={0.48} azimuth={0.25} rayleigh={2} turbidity={8} />

            {/* HDR environment - naturalne odbicia i miękkie oświetlenie */}
            <Environment preset="apartment" background={false} />

            {/* Ambient + kierunkowe słońce */}
            <ambientLight intensity={0.4} />
            <directionalLight
              position={[10, 15, 8]} intensity={1.5} castShadow
              shadow-mapSize-width={2048} shadow-mapSize-height={2048}
              shadow-camera-left={-15} shadow-camera-right={15}
              shadow-camera-top={15} shadow-camera-bottom={-15}
              shadow-camera-near={0.1} shadow-camera-far={50}
              color="#fff5e6"
            />
            {/* Fill light z drugiej strony (imituje odbite światło z nieba) */}
            <directionalLight position={[-5, 8, -6]} intensity={0.35} color="#a3d0ff" />

            <Apartment devices={devices} rooms={rooms} activityByDeviceId={activityByDeviceId} />

            {/* Cień kontaktowy pod mieszkaniem - dodaje głębi */}
            <ContactShadows position={[0, 0.01, 0]} opacity={0.4} scale={20} blur={2} far={2} />

            {/* Delikatna mgła w tle chowa krawędzie sceny */}
            <fog attach="fog" args={['#87ceeb', 25, 55]} />

            {/* Ograniczony zoom - nie widać brzegów sceny */}
            <OrbitControls
              enableDamping dampingFactor={0.1}
              minDistance={4} maxDistance={18}
              maxPolarAngle={Math.PI / 2 - 0.05}
              target={[0, 0, 0]}
            />
          </Suspense>
        </Canvas>
      </div>

      {/* Timeline scrubber - tylko dla ukończonych testów */}
      {isComplete && maxSimMinute > 0 && (
        <div className="mt-3 bg-slate-950 border border-slate-700 rounded p-3">
          <div className="flex items-center gap-3 mb-2">
            <label className="text-xs text-slate-400 flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={scrubberEnabled}
                onChange={(e) => setScrubberEnabled(e.target.checked)}
                className="accent-brand-500"
              />
              Odtwarzanie z timeline
            </label>
            {scrubberEnabled && (
              <>
                <button
                  type="button"
                  onClick={() => setPlaying(!playing)}
                  className="text-xs px-3 py-1 rounded bg-brand-600 hover:bg-brand-700 text-white font-medium"
                >
                  {playing ? '⏸ Pauza' : '▶ Odtwórz'}
                </button>
                <button
                  type="button"
                  onClick={() => setScrubberMinute(0)}
                  className="text-xs px-2 py-1 rounded bg-slate-700 hover:bg-slate-600 text-slate-200"
                >
                  ⏮ Reset
                </button>
                <span className="text-xs text-slate-300 ml-auto font-mono">
                  {formatSimTime(scrubberMinute)} / {formatSimTime(maxSimMinute)}
                </span>
              </>
            )}
          </div>
          {scrubberEnabled && (
            <input
              type="range"
              min={0}
              max={maxSimMinute}
              step={1}
              value={scrubberMinute}
              onChange={(e) => setScrubberMinute(Number(e.target.value))}
              className="w-full accent-brand-500"
            />
          )}
        </div>
      )}

      <div className="mt-3 grid grid-cols-3 gap-2 text-xs text-slate-400">
        <div className="bg-slate-900 border border-slate-700 rounded p-2 text-center">
          <strong className="text-slate-200">Obracanie</strong>: lewy + drag
        </div>
        <div className="bg-slate-900 border border-slate-700 rounded p-2 text-center">
          <strong className="text-slate-200">Przesuwanie</strong>: prawy + drag
        </div>
        <div className="bg-slate-900 border border-slate-700 rounded p-2 text-center">
          <strong className="text-slate-200">Zoom</strong>: scroll
        </div>
      </div>
    </div>
  );
}

// ---- Reużywalny toggle group ----------------------------------------------

interface ToggleGroupProps {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
  disabled?: boolean;
}

function ToggleGroup({ label, value, options, onChange, disabled }: ToggleGroupProps) {
  return (
    <div className={`flex items-center gap-1 ${disabled ? 'opacity-40' : ''}`}>
      {label && <span className="text-xs text-slate-400 mr-1">{label}:</span>}
      <div className="flex bg-slate-950 border border-slate-700 rounded overflow-hidden">
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            disabled={disabled}
            onClick={() => onChange(o.value)}
            className={`px-3 py-1 text-xs font-medium transition ${
              value === o.value ? 'bg-brand-600 text-white' : 'text-slate-300 hover:bg-slate-800'
            } ${disabled ? 'cursor-not-allowed' : ''}`}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

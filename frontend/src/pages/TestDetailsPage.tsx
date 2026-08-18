import { useEffect, useMemo, useRef, useState } from 'react';
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
import { deleteTest, getMeasurements, getTest, listRooms } from '../api';
import type { MeasurementPoint, Room, TestResponse } from '../types';
import StatusBadge from '../components/StatusBadge';
import CostSection from '../components/CostSection';

// Paleta kolorów dla linii na wykresie (cykliczna)
const COLORS = [
  '#3b82f6', '#10b981', '#f59e0b', '#ec4899',
  '#8b5cf6', '#14b8a6', '#ef4444', '#a3e635',
];

type TimeMode = 'sim' | 'real';
type ChartMode = 'combined' | 'separate';
type ScaleMode = 'linear' | 'log';
type ViewMode = 'chart' | 'pattern';

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
    // Polling co 5s (nie 1s) - InfluxDB Cloud free plan ma limit ~300 query/5min.
    // Przy 1s: 60min × 60s = 3600 queries -> zawsze 429.
    // Przy 5s: 60min × 12 = 720 queries -> mieści się w limicie.
    const interval = setInterval(() => {
      const s = statusRef.current;
      if (s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED') return;
      refresh();
    }, 5000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Pobierz listę pokoi (labelki dla grupowania wykresow per pokoj)
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

  // Interwal emisji wyznaczany z danych: wszystkie urzadzenia raportuja w tych samych
  // momentach, wiec mediana odstepu miedzy kolejnymi unikatowymi znacznikami czasu to
  // krok emisji. Poprzednia wersja dzielila przez 60, zakladajac probke co minute -
  // przy domyslnej emisji co 5 min zanizalo to zuzycie dokladnie pieciokrotnie
  // (np. 73 kWh zamiast 365 kWh dla testu 30-dniowego).
  const totalKwh = useMemo(() => {
    if (points.length === 0) return 0;
    const stamps = Array.from(
      new Set(points.map((p) => new Date(p.timestamp).getTime())),
    ).sort((a, b) => a - b);
    let stepMin = (test?.config as { emitEveryNMinutes?: number } | null)?.emitEveryNMinutes ?? 5;
    if (stamps.length > 2) {
      const gaps: number[] = [];
      for (let i = 1; i < stamps.length; i++) gaps.push(stamps[i] - stamps[i - 1]);
      gaps.sort((a, b) => a - b);
      stepMin = Math.max(1, Math.round(gaps[Math.floor(gaps.length / 2)] / 60000));
    }
    return (points.reduce((s, p) => s + p.powerW, 0) * stepMin) / 60 / 1000;
  }, [points, test?.config]);

  // Lista urzadzen z konfiguracji testu (z polem `room` do grupowania wykresow).
  const configDevices = useMemo(() => {
    const cfg = test?.config as { devices?: Array<{ id: string; type: string; room?: string }> } | null;
    return cfg?.devices ?? [];
  }, [test?.config]);

  // Mapa deviceId -> roomType. Wykorzystywana w ChartView trybie "Osobno" do
  // grupowania wykresow per pokoj oraz w widoku "Wzorzec dnia".
  const deviceRoomMap = useMemo(() => {
    const m = new Map<string, string>();
    for (const d of configDevices) {
      if (d.room) m.set(d.id, d.room);
    }
    return m;
  }, [configDevices]);

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
              {viewMode === 'chart' ? 'Pobór mocy w czasie' : 'Wzorzec dnia - kiedy działają urządzenia'}
            </h2>
            <span className="text-xs text-slate-500">({points.length} pomiarów)</span>
          </div>

          <div className="flex gap-2 flex-wrap">
            <ToggleGroup
              label=""
              value={viewMode}
              options={[
                { value: 'chart', label: '📊 Wykres' },
                { value: 'pattern', label: '📅 Wzorzec dnia' },
              ]}
              onChange={(v) => setViewMode(v as ViewMode)}
            />
          </div>
        </div>

        {viewMode === 'chart' ? (
          <ChartView
            rows={rows}
            devices={devices}
            deviceRoomMap={deviceRoomMap}
            rooms={rooms}
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
          <DailyPatternView
            points={points}
            devices={devices}
            deviceRoomMap={deviceRoomMap}
            rooms={rooms}
            startedAt={effectiveStart}
            speedFactor={test.speedFactor}
          />
        )}
      </div>

      {/* Sekcja kosztu - porownanie 3 taryf (G11/G12/RDN).
          testName - do wykrycia sezonu (Zima/Wiosna/Lato/Jesien) i pokazania sensownych presetow. */}
      <div className="mb-6">
        <CostSection
          testId={test.testId}
          testName={test.name}
          durationDays={test.durationDays}
          reloadKey={1}
        />
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
  /** Mapa deviceId -> roomType. Uzywane do grupowania wykresow w trybie "Osobno". */
  deviceRoomMap: Map<string, string>;
  /** Lista pokoi z labelami (np. KITCHEN -> "Kuchnia") - do wyswietlania nagłowkow sekcji. */
  rooms: Room[];
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
  rows, devices, deviceRoomMap, rooms, chartMode, scaleMode, timeMode,
  setChartMode, setScaleMode, setTimeMode, formatTick, formatTooltipLabel,
}: ChartViewProps) {
  // Grupowanie urzadzen po pokoju dla trybu "Osobno".
  // Struktura: Map<roomType, deviceIds[]>. Urzadzenia bez przypisanego pokoju
  // trafiaja do pseudo-grupy "" (wyswietlana jako "Bez pomieszczenia").
  const devicesByRoom = useMemo(() => {
    const grouped = new Map<string, string[]>();
    for (const dev of devices) {
      const room = deviceRoomMap.get(dev) ?? '';
      if (!grouped.has(room)) grouped.set(room, []);
      grouped.get(room)!.push(dev);
    }
    return grouped;
  }, [devices, deviceRoomMap]);

  const roomLabel = (type: string): string => {
    if (!type) return 'Bez pomieszczenia';
    return rooms.find((r) => r.type === type)?.label ?? type;
  };

  // Kolejnosc pokoi: najpierw wg listy `rooms` (systemowe pierwsze),
  // potem urzadzenia bez pokoju na koncu.
  const orderedRoomTypes = useMemo(() => {
    const present = Array.from(devicesByRoom.keys());
    const ordered: string[] = [];
    for (const r of rooms) {
      if (present.includes(r.type)) ordered.push(r.type);
    }
    // Dodaj pokoje ktore sa w devicesByRoom ale nie w rooms (edge case)
    for (const t of present) {
      if (!ordered.includes(t) && t !== '') ordered.push(t);
    }
    // Bez pomieszczenia na koncu
    if (present.includes('')) ordered.push('');
    return ordered;
  }, [devicesByRoom, rooms]);
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
        // Tryb "Osobno" - wykresy pogrupowane wg pokojow.
        // Kazdy pokoj = naglowek + grid wykresow urzadzen tego pokoju.
        <div className="space-y-6">
          {orderedRoomTypes.map((roomType) => {
            const devsInRoom = devicesByRoom.get(roomType) ?? [];
            return (
              <section key={roomType || '__no_room__'}>
                <h3 className="text-sm font-semibold text-slate-300 mb-2 flex items-center gap-2 pb-1 border-b border-slate-700">
                  <span className="text-slate-100">{roomLabel(roomType)}</span>
                  <span className="text-xs text-slate-500 font-normal">
                    ({devsInRoom.length} {devsInRoom.length === 1 ? 'urządzenie' : 'urządzeń'})
                  </span>
                </h3>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {devsInRoom.map((dev) => {
                    // Globalny indeks urzadzenia w liscie devices - dla spojnosci kolorow
                    // z trybem "Razem" (to samo urzadzenie ma ten sam kolor w obu trybach).
                    const globalIdx = devices.indexOf(dev);
                    return (
                      <div key={dev} className="bg-slate-950 border border-slate-700 rounded p-3">
                        <div className="flex items-center gap-2 mb-2">
                          <div className="w-3 h-3 rounded-full" style={{ backgroundColor: COLORS[globalIdx % COLORS.length] }} />
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
                            <Line type="monotone" dataKey={dev} stroke={COLORS[globalIdx % COLORS.length]} strokeWidth={1.5} dot={false} connectNulls />
                          </LineChart>
                        </ResponsiveContainer>
                      </div>
                    );
                  })}
                </div>
              </section>
            );
          })}
        </div>
      )}
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

// ==========================================================================
//  DailyPatternView - wzorzec dnia (Gantt 24h × urządzenia)
// ==========================================================================
//
// Pokazuje "typowy dzień testu" - dla każdego urządzenia i każdej godziny doby
// (0-23) liczymy średnią moc uśrednioną po wszystkich dniach symulacji.
// Kolor komórki = intensywność względna (im ciemniejszy tym więcej mocy).
//
// Timestamp z InfluxDB to wall clock realny - trzeba go przekonwertować na
// czas SYMULOWANY (od momentu startu testu × speedFactor) żeby "godzina 18:00"
// oznaczała 18:00 w symulowanej dobie, nie w realu.

interface DailyPatternViewProps {
  points: MeasurementPoint[];
  devices: string[];
  deviceRoomMap: Map<string, string>;
  rooms: Room[];
  startedAt: string | null;
  speedFactor: number;
}

function DailyPatternView({
  points, devices, deviceRoomMap, rooms,
}: DailyPatternViewProps) {

  // Zbudowanie macierzy [deviceId][hour 0-23] = suma mocy + licznik pomiarów
  // Potem srednia = suma / licznik. Skala kolorów: 0..maxPowerPerDevice.
  //
  // Po fixie TestRunner (bug timestampow) pomiary w Influx maja timestampy juz
  // rozciagniete na cale 30 dni symulacji (nie real time zbite w 1h). Dzieki
  // temu wystarczy wyciagnac godzine doby bezposrednio z timestampa - bez
  // konwersji przez elapsed*speedFactor. Uzycie startedAt i speedFactor
  // usuniete, bo wszystko juz jest w czasie symulowanym.
  const pattern = useMemo(() => {
    if (points.length === 0) {
      return { matrix: new Map<string, number[]>(), maxPerDevice: new Map<string, number>(), counts: new Map<string, number[]>() };
    }
    const sums = new Map<string, number[]>();
    const counts = new Map<string, number[]>();

    for (const p of points) {
      const ts = new Date(p.timestamp);
      const simHour = ts.getHours();
      if (simHour < 0 || simHour > 23) continue;

      if (!sums.has(p.deviceId)) {
        sums.set(p.deviceId, new Array(24).fill(0));
        counts.set(p.deviceId, new Array(24).fill(0));
      }
      sums.get(p.deviceId)![simHour] += p.powerW;
      counts.get(p.deviceId)![simHour] += 1;
    }

    // Srednia moc per godzina + max per urzadzenie (do skali koloru)
    const matrix = new Map<string, number[]>();
    const maxPerDevice = new Map<string, number>();
    for (const [dev, sumArr] of sums.entries()) {
      const cntArr = counts.get(dev)!;
      const avg = sumArr.map((s, i) => (cntArr[i] > 0 ? s / cntArr[i] : 0));
      matrix.set(dev, avg);
      maxPerDevice.set(dev, Math.max(...avg, 1)); // min 1 zeby uniknac dziel przez 0
    }
    return { matrix, maxPerDevice, counts };
  }, [points]);

  // Grupowanie urządzeń po pokoju - dla ładniejszej sekcji per pomieszczenie
  const devicesByRoom = useMemo(() => {
    const grouped = new Map<string, string[]>();
    for (const dev of devices) {
      const room = deviceRoomMap.get(dev) ?? '';
      if (!grouped.has(room)) grouped.set(room, []);
      grouped.get(room)!.push(dev);
    }
    return grouped;
  }, [devices, deviceRoomMap]);

  const roomLabel = (type: string): string => {
    if (!type) return 'Bez pomieszczenia';
    return rooms.find((r) => r.type === type)?.label ?? type;
  };

  const orderedRoomTypes = useMemo(() => {
    const present = Array.from(devicesByRoom.keys());
    const ordered: string[] = [];
    for (const r of rooms) {
      if (present.includes(r.type)) ordered.push(r.type);
    }
    for (const t of present) {
      if (!ordered.includes(t) && t !== '') ordered.push(t);
    }
    if (present.includes('')) ordered.push('');
    return ordered;
  }, [devicesByRoom, rooms]);

  if (points.length === 0) {
    return (
      <div className="text-center text-slate-400 py-8 text-sm">
        Brak pomiarów - poczekaj aż test wygeneruje dane.
      </div>
    );
  }

  return (
    <div>
      <p className="text-xs text-slate-400 mb-3">
        Uśredniony wzorzec dobowy z całego testu. Intensywność koloru = średnia moc w danej godzinie (jasny = mało / wyłączone, ciemny = pełna moc urządzenia).
      </p>

      {/* Header z godzinami 0-23 */}
      <div className="mb-4 overflow-x-auto">
        <div className="min-w-[900px]">
          <div className="grid grid-cols-[180px_1fr] gap-2 items-center mb-2 pb-2 border-b border-slate-700">
            <div className="text-xs text-slate-500 font-semibold">Urządzenie</div>
            <div className="grid gap-0.5 text-[10px] text-slate-500 text-center font-mono" style={{ gridTemplateColumns: 'repeat(24, minmax(0, 1fr))' }}>
              {Array.from({ length: 24 }, (_, h) => (
                <div key={h} className={h % 2 === 0 ? 'text-slate-400' : 'text-slate-600'}>
                  {h.toString().padStart(2, '0')}
                </div>
              ))}
            </div>
          </div>

          {/* Sekcje per pokoj */}
          {orderedRoomTypes.map((roomType) => {
            const devsInRoom = devicesByRoom.get(roomType) ?? [];
            return (
              <div key={roomType || '__no_room__'} className="mb-4">
                <h3 className="text-xs font-semibold text-slate-300 mb-2 uppercase tracking-wider">
                  {roomLabel(roomType)}
                  <span className="ml-2 text-slate-500 font-normal normal-case">
                    ({devsInRoom.length} {devsInRoom.length === 1 ? 'urządzenie' : 'urządzeń'})
                  </span>
                </h3>

                <div className="space-y-1">
                  {devsInRoom.map((dev) => {
                    const globalIdx = devices.indexOf(dev);
                    const baseColor = COLORS[globalIdx % COLORS.length];
                    const avg = pattern.matrix.get(dev) ?? new Array(24).fill(0);
                    const max = pattern.maxPerDevice.get(dev) ?? 1;

                    return (
                      <div key={dev} className="grid grid-cols-[180px_1fr] gap-2 items-center">
                        <div className="flex items-center gap-2 text-xs text-slate-200 truncate">
                          <span className="inline-block w-2.5 h-2.5 rounded-sm shrink-0" style={{ backgroundColor: baseColor }} />
                          <span className="truncate" title={dev}>{dev}</span>
                        </div>
                        <div className="grid gap-0.5" style={{ gridTemplateColumns: 'repeat(24, minmax(0, 1fr))' }}>
                          {avg.map((power, h) => {
                            const intensity = max > 0 ? power / max : 0;
                            const opacity = Math.max(0.05, Math.min(1, intensity));
                            const bgColor = intensity < 0.02 ? '#0f172a' : baseColor;
                            return (
                              <div
                                key={h}
                                className="h-6 rounded-sm relative group cursor-help"
                                style={{
                                  backgroundColor: bgColor,
                                  opacity: intensity < 0.02 ? 1 : opacity,
                                }}
                                title={`${dev} · godzina ${h.toString().padStart(2, '0')}:00 · średnio ${power.toFixed(1)} W (${(intensity * 100).toFixed(0)}% pełnej mocy)`}
                              />
                            );
                          })}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Legenda intensywności */}
      <div className="mt-4 flex items-center gap-3 text-xs text-slate-400 flex-wrap">
        <span>Intensywność:</span>
        <div className="flex items-center gap-1">
          <span>Wył.</span>
          {[0.05, 0.2, 0.4, 0.6, 0.8, 1].map((op) => (
            <div key={op} className="w-4 h-4 rounded-sm bg-slate-400" style={{ opacity: op }} />
          ))}
          <span>Pełna moc</span>
        </div>
        <span className="ml-4 text-slate-500">
          · Godziny to <strong className="text-slate-300">czas symulowany</strong> doby (0-23), uśredniony po dniach testu
        </span>
      </div>
    </div>
  );
}

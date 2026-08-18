import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  createRoom,
  createTemplate,
  createTest,
  deleteRoom,
  deleteTemplate,
  getTemplate,
  listDeviceTypes,
  listRooms,
  listTemplates,
} from '../api';
import type { DeviceType, Room, TemplateSummary } from '../types';

/**
 * Profil zachowania urządzenia - decyduje jaki edytor harmonogramu pokazać.
 *  - ALWAYS_ON: pracuje 24/7 (lodówka, router, bojler, klima)
 *  - RANGES: włączone w przedziałach OD-DO (światło, TV, grzejnik, komputer)
 *  - STARTS: uruchamiane w konkretnej godzinie, cykl kończy się sam (czajnik, pralka, zmywarka, piekarnik)
 */
type Profile = 'ALWAYS_ON' | 'RANGES' | 'STARTS';

const DEVICE_PROFILE: Record<string, Profile> = {
  REFRIGERATOR: 'ALWAYS_ON',
  ROUTER: 'ALWAYS_ON',
  BOILER: 'ALWAYS_ON',
  AC: 'ALWAYS_ON',

  LIGHT: 'RANGES',
  TV: 'RANGES',
  HEATER: 'RANGES',
  COMPUTER: 'RANGES',

  KETTLE: 'STARTS',
  WASHER: 'STARTS',
  DISHWASHER: 'STARTS',
  OVEN: 'STARTS',
};

function profileOf(type: string): Profile {
  return DEVICE_PROFILE[type] ?? 'RANGES';
}

/**
 * Schemat parametrów per typ urządzenia - definiuje jakie inputy pokazać w edytorze.
 * Klucz zgodny z parametrami przyjmowanymi przez backend (snake_case wewnątrz Map<String,Object>).
 */
interface ParamField {
  key: string;
  label: string;
  min: number;
  max: number;
  step: number;
  unit?: string;
}

const PARAM_SCHEMA: Record<string, ParamField[]> = {
  LIGHT: [
    { key: 'power_w', label: 'Moc', min: 1, max: 500, step: 1, unit: 'W' },
  ],
  TV: [
    { key: 'power_w', label: 'Moc', min: 20, max: 500, step: 5, unit: 'W' },
  ],
  HEATER: [
    { key: 'power_w', label: 'Moc grzejnika', min: 500, max: 3500, step: 50, unit: 'W' },
  ],
  ROUTER: [
    { key: 'power_w', label: 'Moc baseload', min: 1, max: 100, step: 1, unit: 'W' },
  ],
  REFRIGERATOR: [
    { key: 'power_w', label: 'Moc kompresora', min: 50, max: 500, step: 10, unit: 'W' },
    { key: 'duty_cycle', label: 'Duty cycle (0-1)', min: 0.05, max: 0.95, step: 0.05 },
    { key: 'cycle_length_minutes', label: 'Długość cyklu', min: 5, max: 120, step: 1, unit: 'min' },
  ],
  AC: [
    { key: 'power_w', label: 'Moc klimatyzacji', min: 300, max: 3000, step: 50, unit: 'W' },
    { key: 'duty_cycle', label: 'Duty cycle (0-1)', min: 0.05, max: 0.95, step: 0.05 },
    { key: 'cycle_length_minutes', label: 'Długość cyklu', min: 10, max: 120, step: 5, unit: 'min' },
  ],
  BOILER: [
    { key: 'power_w', label: 'Moc grzałki', min: 500, max: 3500, step: 100, unit: 'W' },
    { key: 'duty_cycle', label: 'Duty cycle (0-1)', min: 0.05, max: 0.95, step: 0.01 },
    { key: 'cycle_length_minutes', label: 'Długość cyklu', min: 15, max: 240, step: 5, unit: 'min' },
  ],
  WASHER: [
    { key: 'power_w', label: 'Moc pralki', min: 500, max: 3000, step: 50, unit: 'W' },
    { key: 'cycle_minutes', label: 'Długość programu', min: 15, max: 240, step: 5, unit: 'min' },
  ],
  KETTLE: [
    { key: 'power_w', label: 'Moc czajnika', min: 500, max: 3000, step: 50, unit: 'W' },
    { key: 'cycle_minutes', label: 'Czas gotowania', min: 1, max: 15, step: 1, unit: 'min' },
  ],
  OVEN: [
    { key: 'power_w', label: 'Moc grzałki', min: 1000, max: 4000, step: 100, unit: 'W' },
    { key: 'cycle_minutes', label: 'Czas pieczenia', min: 15, max: 240, step: 5, unit: 'min' },
    { key: 'heat_on_minutes', label: 'Grzałka ON', min: 1, max: 30, step: 1, unit: 'min' },
    { key: 'heat_off_minutes', label: 'Grzałka OFF', min: 1, max: 30, step: 1, unit: 'min' },
  ],
  DISHWASHER: [
    { key: 'heat_power_w', label: 'Moc nagrzewania', min: 500, max: 3000, step: 50, unit: 'W' },
    { key: 'wash_power_w', label: 'Moc mycia (pompa)', min: 50, max: 1000, step: 25, unit: 'W' },
    { key: 'dry_power_w', label: 'Moc suszenia', min: 500, max: 3000, step: 50, unit: 'W' },
    { key: 'heat_phase_minutes', label: 'Faza nagrzewania', min: 1, max: 30, step: 1, unit: 'min' },
    { key: 'wash_phase_minutes', label: 'Faza mycia', min: 10, max: 180, step: 5, unit: 'min' },
    { key: 'dry_phase_minutes', label: 'Faza suszenia', min: 1, max: 60, step: 1, unit: 'min' },
  ],
  COMPUTER: [
    { key: 'idle_power_w', label: 'Moc bezczynności', min: 20, max: 500, step: 10, unit: 'W' },
    { key: 'burst_power_w', label: 'Moc obciążenia', min: 50, max: 1000, step: 25, unit: 'W' },
    { key: 'burst_length_minutes', label: 'Długość obciążenia', min: 1, max: 60, step: 1, unit: 'min' },
    { key: 'burst_interval_minutes', label: 'Odstęp obciążeń', min: 5, max: 240, step: 5, unit: 'min' },
  ],
};

function paramsFor(type: string): ParamField[] {
  return PARAM_SCHEMA[type] ?? [];
}

function newLocalKey() {
  return Math.random().toString(36).slice(2, 10);
}

interface ScheduleRange {
  from: string;
  to: string;
}

type ScheduleMode = 'ALWAYS_ON' | 'ALWAYS_OFF' | 'RANGES' | 'STARTS';

interface EditableDevice {
  localKey: string;
  id: string;
  type: string;
  room: string;
  params: Record<string, unknown>;
  scheduleMode: ScheduleMode;
  ranges: ScheduleRange[];
  /** Lista godzin startu w formacie HH:MM (dla urządzeń cyklicznych). */
  starts: string[];
}

/** Domyślny tryb harmonogramu dla nowo dodanego urządzenia. */
function defaultScheduleMode(type: string): ScheduleMode {
  const arch = profileOf(type);
  if (arch === 'ALWAYS_ON') return 'ALWAYS_ON';
  if (arch === 'STARTS') return 'STARTS';
  return 'RANGES';
}

export default function KreatorPage() {
  const navigate = useNavigate();

  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [paletteLoading, setPaletteLoading] = useState(true);
  const [paletteError, setPaletteError] = useState<string | null>(null);

  const [name, setName] = useState('Test z kreatora');
  const [description, setDescription] = useState('');
  const [durationDays, setDurationDays] = useState(1);
  const [speedFactor, setSpeedFactor] = useState(720);
  const [globalTimeMinutes, setGlobalTimeMinutes] = useState(10);
  const [globalPowerPercent, setGlobalPowerPercent] = useState(5);

  const [devices, setDevices] = useState<EditableDevice[]>([]);
  const [modalOpen, setModalOpen] = useState(false);

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Szablony
  const [templates, setTemplates] = useState<TemplateSummary[]>([]);
  const [saveTemplateOpen, setSaveTemplateOpen] = useState(false);
  const [templateError, setTemplateError] = useState<string | null>(null);
  // Aktualnie wybrany szablon w dropdownie - potrzebne zeby przycisk "Usun"
  // wiedzial ktory szablon skasowac.
  const [selectedTemplateId, setSelectedTemplateId] = useState('');

  async function refreshTemplates() {
    try {
      const t = await listTemplates();
      setTemplates(t);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setTemplateError(`Nie udało się pobrać szablonów: ${msg}`);
    }
  }

  useEffect(() => {
    refreshTemplates();
  }, []);

  useEffect(() => {
    Promise.all([listDeviceTypes(), listRooms()])
      .then(([types, roomList]) => {
        setDeviceTypes(types);
        setRooms(roomList);
        setPaletteLoading(false);
      })
      .catch((err) => {
        const msg = err instanceof Error ? err.message : String(err);
        setPaletteError(`Nie udało się pobrać palet: ${msg}`);
        setPaletteLoading(false);
      });
  }, []);

  /** Konwersja EditableDevice do formatu JSON API. */
  function deviceToApiFormat(d: EditableDevice) {
    let schedule: unknown;
    if (d.scheduleMode === 'ALWAYS_ON') {
      schedule = 'always_on';
    } else if (d.scheduleMode === 'ALWAYS_OFF') {
      schedule = 'always_off';
    } else if (d.scheduleMode === 'RANGES') {
      schedule = d.ranges.flatMap((r) => [
        { action: 'ON', at: r.from },
        { action: 'OFF', at: r.to },
      ]);
    } else {
      // STARTS - tylko godziny ON, cykl sam się kończy
      schedule = d.starts.map((at) => ({ action: 'ON', at }));
    }
    return {
      id: d.id,
      type: d.type,
      room: d.room,
      params: d.params,
      schedule,
    };
  }

  const generatedJson = useMemo(() => {
    return {
      name,
      description: description || undefined,
      durationDays,
      speedFactor,
      retentionDays: 30,
      jitter: {
        globalTimeMinutes,
        globalPowerPercent,
      },
      devices: devices.map(deviceToApiFormat),
    };
  }, [name, description, durationDays, speedFactor, globalTimeMinutes, globalPowerPercent, devices]);

  /** Dodaje wiele urządzeń na raz (dla multi-select modala). */
  function addDevices(deviceTypesToAdd: DeviceType[], room: Room) {
    setDevices((prev) => {
      const next = [...prev];
      for (const dt of deviceTypesToAdd) {
        const sameTypeCount = next.filter((d) => d.type === dt.type).length;
        const autoId = `${dt.type.toLowerCase()}_${sameTypeCount + 1}`;
        const mode = defaultScheduleMode(dt.type);
        next.push({
          localKey: newLocalKey(),
          id: autoId,
          type: dt.type,
          room: room.type,
          params: { ...dt.defaultParams },
          scheduleMode: mode,
          ranges: [],
          starts: [],
        });
      }
      return next;
    });
    setModalOpen(false);
  }

  function removeDevice(localKey: string) {
    setDevices((prev) => prev.filter((d) => d.localKey !== localKey));
  }

  function updateDevice(localKey: string, updates: Partial<EditableDevice>) {
    setDevices((prev) => prev.map((d) => (d.localKey === localKey ? { ...d, ...updates } : d)));
  }

  function addRange(localKey: string) {
    const d = devices.find((x) => x.localKey === localKey);
    if (!d) return;
    updateDevice(localKey, {
      ranges: [...d.ranges, { from: '07:00', to: '08:00' }],
    });
  }
  function updateRange(localKey: string, index: number, updates: Partial<ScheduleRange>) {
    const d = devices.find((x) => x.localKey === localKey);
    if (!d) return;
    updateDevice(localKey, {
      ranges: d.ranges.map((r, i) => (i === index ? { ...r, ...updates } : r)),
    });
  }
  function removeRange(localKey: string, index: number) {
    const d = devices.find((x) => x.localKey === localKey);
    if (!d) return;
    updateDevice(localKey, { ranges: d.ranges.filter((_, i) => i !== index) });
  }

  function addStart(localKey: string) {
    const d = devices.find((x) => x.localKey === localKey);
    if (!d) return;
    updateDevice(localKey, { starts: [...d.starts, '07:00'] });
  }
  function updateStart(localKey: string, index: number, value: string) {
    const d = devices.find((x) => x.localKey === localKey);
    if (!d) return;
    updateDevice(localKey, {
      starts: d.starts.map((s, i) => (i === index ? value : s)),
    });
  }
  function removeStart(localKey: string, index: number) {
    const d = devices.find((x) => x.localKey === localKey);
    if (!d) return;
    updateDevice(localKey, { starts: d.starts.filter((_, i) => i !== index) });
  }

  async function handleSubmit() {
    setSubmitError(null);
    if (devices.length === 0) {
      setSubmitError('Dodaj co najmniej jedno urządzenie.');
      return;
    }
    setSubmitting(true);
    try {
      const res = await createTest(generatedJson);
      navigate(`/tests/${res.testId}`);
    } catch (err: unknown) {
      let msg = err instanceof Error ? err.message : String(err);
      if (err && typeof err === 'object' && 'response' in err) {
        const e = err as { response?: { data?: { message?: string } } };
        msg = e.response?.data?.message ?? msg;
      }
      setSubmitError(`Błąd: ${msg}`);
      setSubmitting(false);
    }
  }

  /**
   * Ładuje szablon do stanu kreatora. Konwertuje strukturę JSON API z powrotem
   * na EditableDevice - rekonstruuje scheduleMode i ranges/starts z pola schedule.
   */
  async function handleLoadTemplate(templateId: string) {
    if (!templateId) return;
    try {
      const t = await getTemplate(templateId);
      const cfg = t.config as {
        name?: string;
        description?: string;
        durationDays?: number;
        speedFactor?: number;
        jitter?: { globalTimeMinutes?: number; globalPowerPercent?: number };
        devices?: Array<{
          id: string;
          type: string;
          room?: string;
          params?: Record<string, unknown>;
          schedule?: unknown;
        }>;
      };

      setName(cfg.name ?? 'Test z szablonu');
      setDescription(cfg.description ?? '');
      setDurationDays(cfg.durationDays ?? 1);
      setSpeedFactor(cfg.speedFactor ?? 720);
      setGlobalTimeMinutes(cfg.jitter?.globalTimeMinutes ?? 10);
      setGlobalPowerPercent(cfg.jitter?.globalPowerPercent ?? 5);

      const loadedDevices: EditableDevice[] = (cfg.devices ?? []).map((d) => {
        const arch = profileOf(d.type);
        let scheduleMode: ScheduleMode = defaultScheduleMode(d.type);
        const ranges: ScheduleRange[] = [];
        const starts: string[] = [];

        if (d.schedule === 'always_on') {
          scheduleMode = 'ALWAYS_ON';
        } else if (d.schedule === 'always_off') {
          scheduleMode = 'ALWAYS_OFF';
        } else if (Array.isArray(d.schedule)) {
          const actions = d.schedule as Array<{ action: string; at: string }>;
          if (arch === 'STARTS') {
            scheduleMode = 'STARTS';
            for (const a of actions) {
              if (a.action === 'ON') starts.push(a.at);
            }
          } else {
            scheduleMode = 'RANGES';
            // Parami ON+OFF -> zakres
            for (let i = 0; i < actions.length - 1; i++) {
              if (actions[i].action === 'ON' && actions[i + 1].action === 'OFF') {
                ranges.push({ from: actions[i].at, to: actions[i + 1].at });
                i++;
              }
            }
          }
        }

        return {
          localKey: newLocalKey(),
          id: d.id,
          type: d.type,
          room: d.room ?? '',
          params: d.params ? { ...d.params } : {},
          scheduleMode,
          ranges,
          starts,
        };
      });
      setDevices(loadedDevices);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setTemplateError(`Nie udało się załadować szablonu: ${msg}`);
    }
  }

  async function handleSaveTemplate(tplName: string, tplDescription: string) {
    if (!tplName.trim()) return;
    try {
      await createTemplate(tplName.trim(), tplDescription.trim() || null, generatedJson);
      await refreshTemplates();
      setSaveTemplateOpen(false);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setTemplateError(`Nie udało się zapisać szablonu: ${msg}`);
    }
  }

  async function handleDeleteTemplate(templateId: string) {
    if (!confirm('Usunąć ten szablon?')) return;
    try {
      await deleteTemplate(templateId);
      await refreshTemplates();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setTemplateError(`Nie udało się usunąć szablonu: ${msg}`);
    }
  }

  // ---- Wlasne pomieszczenia (dodaje/usuwa uzytkownik przez kreator) ----

  /** Dodaje nowe pomieszczenie do bazy i odswieza liste pokoi. */
  async function handleAddRoom(label: string): Promise<Room | null> {
    try {
      const created = await createRoom(label);
      const updated = await listRooms();
      setRooms(updated);
      return created;
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      alert(`Nie udało się dodać pomieszczenia: ${msg}`);
      return null;
    }
  }

  /** Usuwa wlasne pomieszczenie (systemowych nie da sie usunac - backend blokuje). */
  async function handleDeleteRoom(type: string) {
    if (!confirm('Usunąć to pomieszczenie? Urządzenia przypisane do niego zostaną osierocone.')) return;
    try {
      await deleteRoom(type);
      const updated = await listRooms();
      setRooms(updated);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      alert(`Nie udało się usunąć pomieszczenia: ${msg}`);
    }
  }

  function labelForRoom(type: string) {
    return rooms.find((r) => r.type === type)?.label ?? type;
  }
  function labelForType(type: string) {
    return deviceTypes.find((t) => t.type === type)?.label ?? type;
  }

  if (paletteLoading) {
    return <div className="text-slate-400">Ładowanie palet...</div>;
  }
  if (paletteError) {
    return (
      <div className="bg-red-900/50 border border-red-700 text-red-200 p-4 rounded">
        {paletteError}
      </div>
    );
  }

  return (
    // pb-24 zeby content nie chowal sie pod sticky action bar na dole
    <div className="pb-24">
      {/* === Header z przyciskami akcji === */}
      <div className="flex items-start justify-between gap-4 flex-wrap mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-100 mb-1">Kreator testu</h1>
          <p className="text-sm text-slate-400">
            Wygodne dodawanie urządzeń bez ręcznego pisania JSON-a.
          </p>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            type="button"
            onClick={() => navigate('/')}
            className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-100 rounded font-medium transition"
          >
            Anuluj
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={submitting || devices.length === 0}
            className="px-5 py-2 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 disabled:cursor-not-allowed text-white rounded font-medium transition shadow-lg shadow-brand-900/50"
          >
            {submitting ? 'Tworzenie...' : '▶ Utwórz test'}
          </button>
        </div>
      </div>

      {/* Kontent - pelna szerokosc (JSON schowany za toggle na dole) */}
      <div className="space-y-6">
          {/* === Sekcja: Szablony === */}
          <div className="bg-slate-800 border-l-4 border-l-blue-500 border-r border-t border-b border-slate-700 rounded p-4">
            <div className="flex items-center justify-between gap-3 flex-wrap">
              <div className="flex items-center gap-2 flex-1 min-w-[240px]">
                <label className="text-sm font-semibold text-slate-200 flex items-center gap-2">
                  <span>📋</span> Szablon:
                </label>
                <select
                  value={selectedTemplateId}
                  onChange={(e) => {
                    const id = e.target.value;
                    setSelectedTemplateId(id);
                    if (id) handleLoadTemplate(id);
                  }}
                  className="flex-1 px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                >
                  <option value="">
                    {templates.length === 0
                      ? 'Brak zapisanych szablonów'
                      : '-- Wczytaj szablon --'}
                  </option>
                  {templates.map((t) => (
                    <option key={t.templateId} value={t.templateId}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={async () => {
                    if (!selectedTemplateId) return;
                    await handleDeleteTemplate(selectedTemplateId);
                    setSelectedTemplateId('');
                  }}
                  disabled={!selectedTemplateId}
                  title="Usuń wybrany szablon z bazy"
                  className="text-xs px-3 py-1.5 rounded bg-red-900 hover:bg-red-800 disabled:bg-slate-800 disabled:text-slate-500 disabled:cursor-not-allowed text-slate-100 font-medium"
                >
                  Usuń wybrany
                </button>
                <button
                  type="button"
                  onClick={() => setSaveTemplateOpen(true)}
                  disabled={devices.length === 0}
                  className="text-xs px-3 py-1.5 rounded bg-slate-700 hover:bg-slate-600 disabled:bg-slate-800 disabled:text-slate-500 disabled:cursor-not-allowed text-slate-100 font-medium"
                >
                  Zapisz jako szablon
                </button>
              </div>
            </div>
            {templateError && (
              <div className="mt-2 text-xs text-red-300">{templateError}</div>
            )}
          </div>

          {/* === Sekcja: Podstawowe parametry === */}
          <div className="bg-slate-800 border-l-4 border-l-purple-500 border-r border-t border-b border-slate-700 rounded p-4">
            <h2 className="text-sm font-semibold text-slate-200 mb-3 flex items-center gap-2">
              <span>⚙️</span> Podstawowe parametry
            </h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <label className="text-xs text-slate-400">
                Nazwa testu
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
              </label>
              <label className="text-xs text-slate-400">
                Opis (opcjonalnie)
                <input
                  type="text"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
              </label>
              <label className="text-xs text-slate-400">
                Dni symulacji
                <input
                  type="number"
                  min={1}
                  max={30}
                  value={durationDays}
                  onChange={(e) => setDurationDays(Number(e.target.value))}
                  className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
              </label>
              <label className="text-xs text-slate-400">
                Speed factor (×szybszy niż realtime)
                <input
                  type="number"
                  min={1}
                  max={1000}
                  value={speedFactor}
                  onChange={(e) => setSpeedFactor(Number(e.target.value))}
                  className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
              </label>
              <label className="text-xs text-slate-400">
                Global jitter time (±min)
                <input
                  type="number"
                  min={0}
                  max={120}
                  value={globalTimeMinutes}
                  onChange={(e) => setGlobalTimeMinutes(Number(e.target.value))}
                  className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
              </label>
              <label className="text-xs text-slate-400">
                Global jitter power (%)
                <input
                  type="number"
                  min={0}
                  max={100}
                  value={globalPowerPercent}
                  onChange={(e) => setGlobalPowerPercent(Number(e.target.value))}
                  className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
              </label>
            </div>
          </div>

          {/* === Sekcja: Urządzenia === */}
          <div className="bg-slate-800 border-l-4 border-l-emerald-500 border-r border-t border-b border-slate-700 rounded p-4">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-sm font-semibold text-slate-200 flex items-center gap-2">
                <span>💡</span> Urządzenia ({devices.length})
              </h2>
              <button
                type="button"
                onClick={() => setModalOpen(true)}
                className="text-xs px-3 py-1.5 rounded bg-brand-600 hover:bg-brand-700 text-white font-medium"
              >
                + Dodaj urządzenia
              </button>
            </div>

            {devices.length === 0 ? (
              <div className="text-sm text-slate-500 py-8 text-center border border-dashed border-slate-700 rounded">
                Brak dodanych urządzeń. Kliknij "Dodaj urządzenia" żeby zacząć.
              </div>
            ) : (
              <div className="space-y-3">
                {devices.map((d) => (
                  <DeviceCard
                    key={d.localKey}
                    device={d}
                    labelForType={labelForType}
                    labelForRoom={labelForRoom}
                    onUpdate={(updates) => updateDevice(d.localKey, updates)}
                    onRemove={() => removeDevice(d.localKey)}
                    onAddRange={() => addRange(d.localKey)}
                    onUpdateRange={(idx, updates) => updateRange(d.localKey, idx, updates)}
                    onRemoveRange={(idx) => removeRange(d.localKey, idx)}
                    onAddStart={() => addStart(d.localKey)}
                    onUpdateStart={(idx, value) => updateStart(d.localKey, idx, value)}
                    onRemoveStart={(idx) => removeStart(d.localKey, idx)}
                  />
                ))}
              </div>
            )}
          </div>

          {submitError && (
            <div className="bg-red-900/50 border border-red-700 text-red-200 p-3 rounded text-sm">
              {submitError}
            </div>
          )}

          {/* === Sekcja: Podglad JSON (schowany za toggle) === */}
          <details className="bg-slate-800 border-l-4 border-l-slate-500 border-r border-t border-b border-slate-700 rounded p-4">
            <summary className="text-sm font-semibold text-slate-200 cursor-pointer flex items-center gap-2 select-none">
              <span>💾</span> Podgląd JSON konfiguracji
              <span className="text-xs text-slate-500 font-normal">(kliknij żeby zobaczyć)</span>
            </summary>
            <pre className="mt-3 text-xs text-slate-300 bg-slate-950 p-3 rounded border border-slate-700 overflow-auto max-h-[500px] font-mono">
              {JSON.stringify(generatedJson, null, 2)}
            </pre>
          </details>
        </div>

      {/* === STICKY ACTION BAR na dole ekranu === */}
      {/* Fixed position - zawsze widoczny nad zawartoscia strony niezaleznie od scrolla */}
      <div className="fixed bottom-0 left-0 right-0 z-40 bg-slate-900/95 backdrop-blur-sm border-t border-slate-700 shadow-lg">
        <div className="max-w-6xl mx-auto px-6 py-3 flex items-center justify-between gap-4 flex-wrap">
          <div className="text-xs text-slate-400">
            {devices.length === 0
              ? <span className="text-slate-500">Dodaj urządzenia żeby utworzyć test</span>
              : <><strong className="text-slate-200">{devices.length}</strong> {devices.length === 1 ? 'urządzenie' : 'urządzeń'} · <strong className="text-slate-200">{durationDays}</strong> dni symulacji · <strong className="text-slate-200">×{speedFactor}</strong> przyspieszenie</>
            }
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => navigate('/')}
              className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-100 rounded font-medium transition text-sm"
            >
              Anuluj
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={submitting || devices.length === 0}
              className="px-6 py-2 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 disabled:cursor-not-allowed text-white rounded font-semibold transition text-sm shadow-lg shadow-brand-900/50"
            >
              {submitting ? 'Tworzenie...' : '▶ Utwórz test'}
            </button>
          </div>
        </div>
      </div>

      {modalOpen && (
        <AddDeviceModal
          deviceTypes={deviceTypes}
          rooms={rooms}
          onClose={() => setModalOpen(false)}
          onAdd={addDevices}
          onAddRoom={handleAddRoom}
          onDeleteRoom={handleDeleteRoom}
        />
      )}

      {saveTemplateOpen && (
        <SaveTemplateModal
          defaultName={name}
          defaultDescription={description}
          templates={templates}
          onClose={() => setSaveTemplateOpen(false)}
          onSave={handleSaveTemplate}
          onDelete={handleDeleteTemplate}
        />
      )}
    </div>
  );
}

// ---- Karta pojedynczego urządzenia z edytorem harmonogramu ------------------

interface DeviceCardProps {
  device: EditableDevice;
  labelForType: (t: string) => string;
  labelForRoom: (t: string) => string;
  onUpdate: (updates: Partial<EditableDevice>) => void;
  onRemove: () => void;
  onAddRange: () => void;
  onUpdateRange: (idx: number, updates: Partial<ScheduleRange>) => void;
  onRemoveRange: (idx: number) => void;
  onAddStart: () => void;
  onUpdateStart: (idx: number, value: string) => void;
  onRemoveStart: (idx: number) => void;
}

function DeviceCard({
  device,
  labelForType,
  labelForRoom,
  onUpdate,
  onRemove,
  onAddRange,
  onUpdateRange,
  onRemoveRange,
  onAddStart,
  onUpdateStart,
  onRemoveStart,
}: DeviceCardProps) {
  const profile = profileOf(device.type);
  const paramFields = paramsFor(device.type);
  const [paramsOpen, setParamsOpen] = useState(false);

  function updateParam(key: string, value: number) {
    onUpdate({ params: { ...device.params, [key]: value } });
  }

  // Opcje trybu zależne od profilu
  const modeOptions: { value: ScheduleMode; label: string }[] = useMemo(() => {
    if (profile === 'ALWAYS_ON') {
      return [
        { value: 'ALWAYS_ON', label: 'Zawsze włączone (24/7)' },
        { value: 'ALWAYS_OFF', label: 'Wyłączone' },
      ];
    }
    if (profile === 'STARTS') {
      return [
        { value: 'STARTS', label: 'Uruchamiaj o godzinach (cykl sam się skończy)' },
        { value: 'ALWAYS_OFF', label: 'Wyłączone' },
      ];
    }
    return [
      { value: 'RANGES', label: 'Włączone w przedziałach (od-do)' },
      { value: 'ALWAYS_ON', label: 'Zawsze włączone (24/7)' },
      { value: 'ALWAYS_OFF', label: 'Wyłączone' },
    ];
  }, [profile]);

  return (
    <div className="bg-slate-950 border border-slate-700 rounded p-3">
      <div className="flex items-center gap-3 mb-3">
        <div className="flex-1 grid grid-cols-1 md:grid-cols-3 gap-2 text-sm">
          <div>
            <div className="text-xs text-slate-500">Typ</div>
            <div className="text-slate-200">{labelForType(device.type)}</div>
          </div>
          <div>
            <div className="text-xs text-slate-500">Pomieszczenie</div>
            <div className="text-slate-200">{labelForRoom(device.room)}</div>
          </div>
          <div>
            <div className="text-xs text-slate-500">ID</div>
            <input
              type="text"
              value={device.id}
              onChange={(e) => onUpdate({ id: e.target.value })}
              className="w-full px-2 py-1 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
            />
          </div>
        </div>
        <button
          type="button"
          onClick={onRemove}
          className="text-xs px-2 py-1 rounded bg-red-900 hover:bg-red-800 text-red-100"
          title="Usuń urządzenie"
        >
          ✕
        </button>
      </div>

      {/* Sekcja parametrów - rozwijana */}
      {paramFields.length > 0 && (
        <div className="border-t border-slate-800 pt-3 mb-3">
          <button
            type="button"
            onClick={() => setParamsOpen((v) => !v)}
            className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1"
          >
            <span>{paramsOpen ? '▼' : '▶'}</span>
            <span>Parametry ({paramFields.length})</span>
          </button>
          {paramsOpen && (
            <div className="mt-2 grid grid-cols-1 md:grid-cols-2 gap-2">
              {paramFields.map((f) => {
                const rawValue = device.params[f.key];
                const numValue =
                  typeof rawValue === 'number' ? rawValue : Number(rawValue ?? f.min);
                return (
                  <label key={f.key} className="text-xs text-slate-400 flex items-center gap-2">
                    <span className="w-40 shrink-0">
                      {f.label}
                      {f.unit ? ` [${f.unit}]` : ''}
                    </span>
                    <input
                      type="number"
                      min={f.min}
                      max={f.max}
                      step={f.step}
                      value={numValue}
                      onChange={(e) => updateParam(f.key, Number(e.target.value))}
                      className="flex-1 px-2 py-1 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                    />
                  </label>
                );
              })}
            </div>
          )}
        </div>
      )}

      <div className="border-t border-slate-800 pt-3">
        <div className="flex items-center gap-3 mb-2">
          <label className="text-xs text-slate-400">
            Tryb pracy:
            <select
              value={device.scheduleMode}
              onChange={(e) => onUpdate({ scheduleMode: e.target.value as ScheduleMode })}
              className="ml-2 px-2 py-1 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
            >
              {modeOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        {/* Edytor RANGES - lista przedziałów OD-DO */}
        {device.scheduleMode === 'RANGES' && (
          <div className="space-y-2">
            {device.ranges.length === 0 && (
              <div className="text-xs text-slate-500 italic">
                Brak przedziałów. Kliknij "+ Dodaj przedział" żeby ustawić kiedy urządzenie ma
                działać.
              </div>
            )}
            {device.ranges.map((r, i) => (
              <div key={i} className="flex items-center gap-2 text-sm">
                <span className="text-xs text-slate-400 w-8">Od</span>
                <input
                  type="time"
                  value={r.from}
                  onChange={(e) => onUpdateRange(i, { from: e.target.value })}
                  className="px-2 py-1 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
                <span className="text-xs text-slate-400 w-8">Do</span>
                <input
                  type="time"
                  value={r.to}
                  onChange={(e) => onUpdateRange(i, { to: e.target.value })}
                  className="px-2 py-1 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
                <button
                  type="button"
                  onClick={() => onRemoveRange(i)}
                  className="text-xs px-2 py-1 rounded bg-red-900 hover:bg-red-800 text-red-100"
                >
                  ✕
                </button>
              </div>
            ))}
            <button
              type="button"
              onClick={onAddRange}
              className="text-xs px-2 py-1 rounded bg-slate-700 hover:bg-slate-600 text-slate-200"
            >
              + Dodaj przedział
            </button>
          </div>
        )}

        {/* Edytor STARTS - lista godzin uruchomienia (cykl sam się kończy) */}
        {device.scheduleMode === 'STARTS' && (
          <div className="space-y-2">
            {device.starts.length === 0 && (
              <div className="text-xs text-slate-500 italic">
                Brak uruchomień. Kliknij "+ Dodaj uruchomienie" żeby ustawić o której godzinie
                urządzenie ma się włączyć. Cykl skończy się sam.
              </div>
            )}
            {device.starts.map((s, i) => (
              <div key={i} className="flex items-center gap-2 text-sm">
                <span className="text-xs text-slate-400 w-16">Start o</span>
                <input
                  type="time"
                  value={s}
                  onChange={(e) => onUpdateStart(i, e.target.value)}
                  className="px-2 py-1 bg-slate-800 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
                <button
                  type="button"
                  onClick={() => onRemoveStart(i)}
                  className="text-xs px-2 py-1 rounded bg-red-900 hover:bg-red-800 text-red-100"
                >
                  ✕
                </button>
              </div>
            ))}
            <button
              type="button"
              onClick={onAddStart}
              className="text-xs px-2 py-1 rounded bg-slate-700 hover:bg-slate-600 text-slate-200"
            >
              + Dodaj uruchomienie
            </button>
          </div>
        )}

        {/* ALWAYS_ON / ALWAYS_OFF - nic dodatkowego */}
        {(device.scheduleMode === 'ALWAYS_ON' || device.scheduleMode === 'ALWAYS_OFF') && (
          <div className="text-xs text-slate-500 italic">
            {device.scheduleMode === 'ALWAYS_ON'
              ? 'Urządzenie pracuje przez całą dobę symulacji.'
              : 'Urządzenie jest wyłączone przez całą dobę.'}
          </div>
        )}
      </div>
    </div>
  );
}

// ---- Modal z multi-select ---------------------------------------------------

interface AddDeviceModalProps {
  deviceTypes: DeviceType[];
  rooms: Room[];
  onClose: () => void;
  onAdd: (types: DeviceType[], room: Room) => void;
  /** Dodaje wlasny pokoj przez API, zwraca nowo utworzony pokoj albo null jesli blad. */
  onAddRoom: (label: string) => Promise<Room | null>;
  /** Usuwa wlasny pokoj (systemowych nie da sie usunac). */
  onDeleteRoom: (type: string) => Promise<void>;
}

function AddDeviceModal({ deviceTypes, rooms, onClose, onAdd, onAddRoom, onDeleteRoom }: AddDeviceModalProps) {
  const [selectedRoom, setSelectedRoom] = useState<Room | null>(null);
  const [selectedTypeKeys, setSelectedTypeKeys] = useState<Set<string>>(new Set());
  const [addingRoom, setAddingRoom] = useState(false);
  const [newRoomLabel, setNewRoomLabel] = useState('');

  async function handleAddRoomClick() {
    const label = newRoomLabel.trim();
    if (!label) return;
    const created = await onAddRoom(label);
    if (created) {
      setSelectedRoom(created);
      setNewRoomLabel('');
      setAddingRoom(false);
    }
  }

  function toggleType(t: DeviceType) {
    setSelectedTypeKeys((prev) => {
      const next = new Set(prev);
      if (next.has(t.type)) {
        next.delete(t.type);
      } else {
        next.add(t.type);
      }
      return next;
    });
  }

  function handleAdd() {
    if (!selectedRoom) return;
    const types = deviceTypes.filter((t) => selectedTypeKeys.has(t.type));
    if (types.length === 0) return;
    onAdd(types, selectedRoom);
  }

  // Zmiana pokoju - nie czyscimy zaznaczonych typow, bo teraz kazde urzadzenie
  // moze byc dodane do dowolnego pokoju.
  function handleRoomChange(r: Room) {
    setSelectedRoom(r);
  }

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4"
      onClick={onClose}
    >
      <div
        className="bg-slate-800 border border-slate-700 rounded-lg p-6 max-w-3xl w-full max-h-[85vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-slate-100">Dodaj urządzenia</h2>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-200 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        <p className="text-xs text-slate-500 mb-4">
          Wybierz pomieszczenie, potem zaznacz jedno lub więcej urządzeń do dodania. Wszystkie
          zaznaczone zostaną dodane naraz.
        </p>

        {/* Krok 1: pomieszczenie */}
        <div className="mb-6">
          <h3 className="text-sm font-medium text-slate-300 mb-2">1. Wybierz pomieszczenie</h3>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
            {rooms.map((r) => (
              <div key={r.type} className="relative">
                <button
                  type="button"
                  onClick={() => handleRoomChange(r)}
                  className={`w-full px-3 py-2 rounded text-sm text-left transition border ${
                    selectedRoom?.type === r.type
                      ? 'bg-brand-600 border-brand-500 text-white'
                      : 'bg-slate-950 border-slate-700 text-slate-200 hover:bg-slate-900'
                  }`}
                >
                  <div className="font-medium pr-5">{r.label}</div>
                  <div className="text-xs opacity-70">{r.type}</div>
                </button>
                {/* Przycisk usuwania tylko dla wlasnych pokoi (non-system) */}
                {!r.system && (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onDeleteRoom(r.type).then(() => {
                        if (selectedRoom?.type === r.type) setSelectedRoom(null);
                      });
                    }}
                    title="Usuń to pomieszczenie"
                    className="absolute top-1 right-1 w-5 h-5 flex items-center justify-center rounded bg-red-900/80 hover:bg-red-700 text-white text-xs leading-none"
                  >
                    ×
                  </button>
                )}
              </div>
            ))}
          </div>

          {/* Sekcja dodawania nowego pomieszczenia */}
          <div className="mt-3">
            {!addingRoom ? (
              <button
                type="button"
                onClick={() => setAddingRoom(true)}
                className="text-xs px-3 py-1.5 rounded bg-slate-700 hover:bg-slate-600 text-slate-100"
              >
                + Dodaj własne pomieszczenie
              </button>
            ) : (
              <div className="flex gap-2 items-center">
                <input
                  type="text"
                  value={newRoomLabel}
                  onChange={(e) => setNewRoomLabel(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') { e.preventDefault(); handleAddRoomClick(); }
                    if (e.key === 'Escape') { setAddingRoom(false); setNewRoomLabel(''); }
                  }}
                  placeholder="Np. Garaż, Taras, Piwnica..."
                  autoFocus
                  className="flex-1 px-3 py-1.5 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
                />
                <button
                  type="button"
                  onClick={handleAddRoomClick}
                  disabled={!newRoomLabel.trim()}
                  className="text-xs px-3 py-1.5 rounded bg-brand-600 hover:bg-brand-700 disabled:bg-slate-800 disabled:text-slate-500 text-white"
                >
                  Dodaj
                </button>
                <button
                  type="button"
                  onClick={() => { setAddingRoom(false); setNewRoomLabel(''); }}
                  className="text-xs px-3 py-1.5 rounded bg-slate-700 hover:bg-slate-600 text-slate-100"
                >
                  Anuluj
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Krok 2: typy urządzeń - multi-select */}
        <div className="mb-6">
          <h3 className="text-sm font-medium text-slate-300 mb-2">
            2. Zaznacz urządzenia ({selectedTypeKeys.size} wybranych)
          </h3>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
            {deviceTypes.map((t) => {
              const checked = selectedTypeKeys.has(t.type);
              return (
                <label
                  key={t.type}
                  className={`px-3 py-2 rounded text-sm text-left transition border flex items-start gap-2 cursor-pointer ${
                    checked
                      ? 'bg-brand-900 border-brand-500 text-white'
                      : 'bg-slate-950 border-slate-700 text-slate-200 hover:bg-slate-900'
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => toggleType(t)}
                    className="mt-0.5 accent-brand-500"
                  />
                  <div>
                    <div className="font-medium">{t.label}</div>
                    <div className="text-xs opacity-70">{t.type}</div>
                  </div>
                </label>
              );
            })}
          </div>
        </div>

        <div className="flex gap-3 justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-100 rounded text-sm"
          >
            Anuluj
          </button>
          <button
            type="button"
            onClick={handleAdd}
            disabled={!selectedRoom || selectedTypeKeys.size === 0}
            className="px-4 py-2 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 disabled:cursor-not-allowed text-white rounded text-sm font-medium"
          >
            Dodaj {selectedTypeKeys.size > 0 ? `(${selectedTypeKeys.size})` : ''}
          </button>
        </div>
      </div>
    </div>
  );
}

// ---- Modal zapisu szablonu -------------------------------------------------

interface SaveTemplateModalProps {
  defaultName: string;
  defaultDescription: string;
  templates: TemplateSummary[];
  onClose: () => void;
  onSave: (name: string, description: string) => void;
  onDelete: (templateId: string) => void;
}

function SaveTemplateModal({
  defaultName,
  defaultDescription,
  templates,
  onClose,
  onSave,
  onDelete,
}: SaveTemplateModalProps) {
  const [name, setName] = useState(defaultName);
  const [description, setDescription] = useState(defaultDescription);

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4"
      onClick={onClose}
    >
      <div
        className="bg-slate-800 border border-slate-700 rounded-lg p-6 max-w-lg w-full max-h-[85vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-slate-100">Zapisz jako szablon</h2>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-200 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        <div className="space-y-3 mb-4">
          <label className="text-xs text-slate-400 block">
            Nazwa szablonu
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500"
              autoFocus
            />
          </label>
          <label className="text-xs text-slate-400 block">
            Opis (opcjonalnie)
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              className="mt-1 w-full px-3 py-2 bg-slate-950 border border-slate-700 rounded text-slate-100 text-sm focus:outline-none focus:border-brand-500 resize-none"
            />
          </label>
        </div>

        {templates.length > 0 && (
          <div className="mb-4">
            <h3 className="text-xs font-medium text-slate-300 mb-2">
              Zapisane szablony ({templates.length})
            </h3>
            <div className="space-y-1 max-h-40 overflow-y-auto">
              {templates.map((t) => (
                <div
                  key={t.templateId}
                  className="flex items-center justify-between gap-2 text-xs bg-slate-950 border border-slate-700 rounded p-2"
                >
                  <div className="flex-1 min-w-0">
                    <div className="text-slate-200 truncate">{t.name}</div>
                    {t.description && (
                      <div className="text-slate-500 truncate">{t.description}</div>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => onDelete(t.templateId)}
                    className="text-xs px-2 py-1 rounded bg-red-900 hover:bg-red-800 text-red-100 shrink-0"
                    title="Usuń szablon"
                  >
                    ✕
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="flex gap-3 justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-100 rounded text-sm"
          >
            Anuluj
          </button>
          <button
            type="button"
            onClick={() => onSave(name, description)}
            disabled={!name.trim()}
            className="px-4 py-2 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 disabled:cursor-not-allowed text-white rounded text-sm font-medium"
          >
            Zapisz
          </button>
        </div>
      </div>
    </div>
  );
}

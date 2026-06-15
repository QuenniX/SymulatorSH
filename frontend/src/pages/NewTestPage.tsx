import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createTest } from '../api';

const TEMPLATE = `{
  "name": "Test dobowy - kuchnia",
  "description": "Lodowka + lampa kuchenna + pralka noca",
  "durationDays": 1,
  "speedFactor": 720,
  "jitter": {
    "globalTimeMinutes": 10,
    "globalPowerPercent": 5
  },
  "devices": [
    {
      "id": "fridge_01",
      "type": "REFRIGERATOR",
      "params": { "power_w": 150, "duty_cycle": 0.4 },
      "schedule": "always_on"
    },
    {
      "id": "light_kitchen",
      "type": "LIGHT",
      "params": { "power_w": 60 },
      "schedule": [
        { "action": "ON",  "at": "07:00" },
        { "action": "OFF", "at": "08:00" },
        { "action": "ON",  "at": "19:00" },
        { "action": "OFF", "at": "23:00" }
      ]
    },
    {
      "id": "washer_01",
      "type": "WASHER",
      "params": { "power_w": 2000, "cycle_minutes": 60 },
      "schedule": [
        { "action": "ON", "at": "22:30", "durationMinutes": 60 }
      ]
    }
  ]
}`;

export default function NewTestPage() {
  const navigate = useNavigate();
  const [json, setJson] = useState(TEMPLATE);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);

    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(`Niepoprawny JSON: ${msg}`);
      setSubmitting(false);
      return;
    }

    try {
      const res = await createTest(parsed);
      navigate(`/tests/${res.testId}`);
    } catch (err: unknown) {
      let msg = err instanceof Error ? err.message : String(err);
      if (err && typeof err === 'object' && 'response' in err) {
        const e = err as { response?: { data?: { message?: string } } };
        msg = e.response?.data?.message ?? msg;
      }
      setError(`Błąd: ${msg}`);
      setSubmitting(false);
    }
  }

  function loadTemplate() {
    setJson(TEMPLATE);
  }

  function clearAll() {
    setJson('');
  }

  return (
    <div>
      <h1 className="text-2xl font-bold text-slate-100 mb-2">Nowy test</h1>
      <p className="text-sm text-slate-400 mb-6">
        Wprowadź konfigurację testu w formacie JSON. Sprawdź{' '}
        <a
          href="https://github.com/QuenniX/SymulatorSH/blob/main/docs/json-schema.md"
          target="_blank"
          rel="noreferrer"
          className="text-brand-500 hover:underline"
        >
          dokumentację formatu
        </a>{' '}
        po szczegóły pól.
      </p>

      <form onSubmit={handleSubmit}>
        <div className="bg-slate-800 border border-slate-700 rounded p-4 mb-4">
          <div className="flex items-center justify-between mb-2">
            <label className="text-sm font-medium text-slate-300">Konfiguracja JSON</label>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={loadTemplate}
                className="text-xs px-2 py-1 rounded bg-slate-700 hover:bg-slate-600 text-slate-200"
              >
                Załaduj szablon
              </button>
              <button
                type="button"
                onClick={clearAll}
                className="text-xs px-2 py-1 rounded bg-slate-700 hover:bg-slate-600 text-slate-200"
              >
                Wyczyść
              </button>
            </div>
          </div>
          <textarea
            value={json}
            onChange={(e) => setJson(e.target.value)}
            className="w-full h-[440px] p-3 bg-slate-950 border border-slate-700 rounded font-mono text-sm text-slate-100 focus:outline-none focus:border-brand-500 resize-none"
            spellCheck={false}
          />
        </div>

        {error && (
          <div className="bg-red-900/50 border border-red-700 text-red-200 p-3 rounded mb-4 text-sm">
            {error}
          </div>
        )}

        <div className="flex gap-3 justify-end">
          <button
            type="button"
            onClick={() => navigate('/')}
            className="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-slate-100 rounded font-medium transition"
          >
            Anuluj
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="px-6 py-2 bg-brand-600 hover:bg-brand-700 disabled:bg-slate-600 disabled:cursor-not-allowed text-white rounded font-medium transition"
          >
            {submitting ? 'Tworzenie...' : 'Utwórz test'}
          </button>
        </div>
      </form>
    </div>
  );
}

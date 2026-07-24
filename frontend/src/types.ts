// Typy DTO zwracane przez backend

export type TestStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface TestSummary {
  testId: string;
  name: string;
  status: TestStatus;
  durationDays: number;
  speedFactor: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface TestResponse {
  testId: string;
  name: string;
  description: string | null;
  status: TestStatus;
  config: unknown;
  durationDays: number;
  speedFactor: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  realDurationSeconds: number | null;
  errorMessage: string | null;
}

export interface CreateTestResponse {
  testId: string;
  status: TestStatus;
  createdAt: string;
}

export interface DeviceType {
  type: string;
  label: string;
  defaultParams: Record<string, unknown>;
}

export interface Room {
  type: string;
  label: string;
}

export interface TemplateSummary {
  templateId: string;
  name: string;
  description: string | null;
  createdAt: string;
}

export interface TemplateResponse {
  templateId: string;
  name: string;
  description: string | null;
  config: unknown;
  createdAt: string;
}

export interface CreateTemplateResponse {
  templateId: string;
  createdAt: string;
}

/** Urządzenie w state kreatora - przed serializacją do JSON-a testu. */
export interface DeviceInstance {
  /** Lokalny id na potrzeby listy React (nie mylić z id urządzenia w JSON). */
  localKey: string;
  id: string;
  type: string;
  room: string;
  params: Record<string, unknown>;
  /** Uproszczony schedule dla MVP: 'always_on' albo pusta tablica. */
  schedule: 'always_on' | unknown[];
}

export interface MeasurementPoint {
  timestamp: string;
  deviceId: string;
  powerW: number;
}

export interface MeasurementsResponse {
  testId: string;
  points: MeasurementPoint[];
}

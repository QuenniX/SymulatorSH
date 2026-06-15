// Typy DTO zwracane przez backend - zgodne z docs/api-spec.md

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

export interface MeasurementPoint {
  timestamp: string;
  deviceId: string;
  powerW: number;
}

export interface MeasurementsResponse {
  testId: string;
  points: MeasurementPoint[];
}

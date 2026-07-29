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

export interface EnergyPrice {
  market: string;
  deliveryDate: string;   // ISO date YYYY-MM-DD
  hour: number;           // 0-23
  pricePlnMwh: number;
  pricePlnKwh: number;
}

export interface FetchPricesResult {
  date: string;
  savedRecords: number;
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

// ---- Batch runner ----

export interface BatchCreateRequest {
  templateIds: string[];
  durationDays: number;
  speedFactor: number;
  emitEveryNMinutes?: number;
  namePrefix?: string;
}

export interface BatchFailure {
  templateId: string;
  templateName: string;
  errorMessage: string;
}

export interface BatchCreateResponse {
  requestedCount: number;
  createdCount: number;
  failedCount: number;
  createdTestIds: string[];
  failures: BatchFailure[];
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

// ---- Koszt testu w 3 taryfach --------------------------------------------

export type TariffCode = 'G11' | 'G12' | 'RDN';

export interface HourlyCost {
  hour: string;              // ISO LocalDateTime, np. "2026-07-24T19:00:00"
  kwh: number;
  g11PricePlnKwh: number;
  g12PricePlnKwh: number;
  rdnPricePlnKwh: number;
  g11CostPln: number;
  g12CostPln: number;
  rdnCostPln: number;
  rdnPriceMissing: boolean;
}

export interface CostBreakdown {
  testId: string;
  year: number;
  hoursAnalyzed: number;
  hoursWithMissingRdnPrice: number;
  totalKwh: number;
  totalCostG11Pln: number;
  totalCostG12Pln: number;
  totalCostRdnPln: number;
  cheapestTariff: TariffCode;
  rdnVsG11SavingsPercent: number;   // dodatnia = RDN taniej
  hourlyBreakdown: HourlyCost[];
}

// ---- Projekcja historyczna (bootstrap) ----

export interface DailyCostPoint {
  date: string;              // ISO date "YYYY-MM-DD"
  kwh: number;
  costG11Pln: number;
  costG12Pln: number;
  costRdnPln: number;
}

export interface ProjectedCostBreakdown {
  testId: string;
  from: string;              // ISO date
  to: string;                // ISO date
  year: number;
  daysInPeriod: number;
  daysWithFullPrices: number;
  avgDailyKwh: number;
  totalKwh: number;
  totalCostG11Pln: number;
  totalCostG12Pln: number;
  totalCostRdnPln: number;
  cheapestTariff: TariffCode;
  rdnVsG11SavingsPercent: number;
  rdnDailyMinPln: number;
  rdnDailyMaxPln: number;
  rdnDailyMedianPln: number;
  rdnVaR5PercentPln: number;
  dailyBreakdown: DailyCostPoint[];
}

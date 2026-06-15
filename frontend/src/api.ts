import axios from 'axios';
import type {
  CreateTestResponse,
  DeviceType,
  MeasurementsResponse,
  TestResponse,
  TestSummary,
} from './types';

const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

export async function listTests(): Promise<TestSummary[]> {
  const res = await api.get<TestSummary[]>('/tests');
  return res.data;
}

export async function getTest(id: string): Promise<TestResponse> {
  const res = await api.get<TestResponse>(`/tests/${id}`);
  return res.data;
}

export async function createTest(config: unknown): Promise<CreateTestResponse> {
  const res = await api.post<CreateTestResponse>('/tests', config);
  return res.data;
}

export async function deleteTest(id: string): Promise<void> {
  await api.delete(`/tests/${id}`);
}

export async function getMeasurements(
  id: string,
  deviceId?: string,
): Promise<MeasurementsResponse> {
  const params = deviceId ? { device_id: deviceId } : undefined;
  const res = await api.get<MeasurementsResponse>(`/tests/${id}/measurements`, { params });
  return res.data;
}

export async function listDeviceTypes(): Promise<DeviceType[]> {
  const res = await api.get<DeviceType[]>('/device-types');
  return res.data;
}
